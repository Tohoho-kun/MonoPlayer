//! Native Vehicle Speed & Tunnel Dead-Reckoning Engine for MonoPlayer.
//!
//! Provides zero-allocation, drift-compensated accelerometer integration, road vibration energy
//! analysis, ZUPT (Zero Velocity Update) detection, and acoustic adaptation calculations.

use crate::math_guard::sanitize_f32;

/// Tracking source codes corresponding to Kotlin's `SpeedTrackingSource`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpeedSourceCode {
    Off = 0,
    Gps = 1,
    TunnelImu = 2,
    Stopped = 3,
}

pub struct VehicleTrackerEngine {
    last_gps_speed_kmh: f32,
    estimated_speed_kmh: f32,
    smoothed_speed_kmh: f32,
    vibration_high_pass: f32,
    forward_accel_lp: f32,
    vibration_energy_rms: f32,
}

impl Default for VehicleTrackerEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl VehicleTrackerEngine {
    pub const VIBRATION_STOP_THRESHOLD: f32 = 0.08;

    pub fn new() -> Self {
        Self {
            last_gps_speed_kmh: 0.0,
            estimated_speed_kmh: 0.0,
            smoothed_speed_kmh: 0.0,
            vibration_high_pass: 0.0,
            forward_accel_lp: 0.0,
            vibration_energy_rms: 0.0,
        }
    }

    pub fn reset(&mut self) {
        self.last_gps_speed_kmh = 0.0;
        self.estimated_speed_kmh = 0.0;
        self.smoothed_speed_kmh = 0.0;
        self.vibration_high_pass = 0.0;
        self.forward_accel_lp = 0.0;
        self.vibration_energy_rms = 0.0;
    }

    /// Handles a new GPS speed measurement (km/h).
    /// Returns the smoothed speed (km/h) and source code (1 = GPS).
    pub fn on_gps_speed(&mut self, speed_kmh: f32) -> (f32, i32) {
        let valid_speed = sanitize_f32(speed_kmh).max(0.0);
        self.last_gps_speed_kmh = valid_speed;
        self.estimated_speed_kmh = valid_speed;

        let alpha_speed = 0.18;
        self.smoothed_speed_kmh += alpha_speed * (valid_speed - self.smoothed_speed_kmh);
        self.smoothed_speed_kmh = sanitize_f32(self.smoothed_speed_kmh).max(0.0);

        (self.smoothed_speed_kmh, SpeedSourceCode::Gps as i32)
    }

    /// Processes accelerometer vector (ax, ay, az in m/s^2) and time delta (dt in seconds).
    /// When GPS is lost, dead-reckoning seamlessly infers vehicle motion or standstill.
    /// Returns `(smoothed_speed_kmh, source_code)`.
    pub fn on_sensor_accelerometer(
        &mut self,
        ax: f32,
        ay: f32,
        az: f32,
        dt_sec: f32,
        is_gps_lost: bool,
    ) -> (f32, i32) {
        let ax = sanitize_f32(ax);
        let ay = sanitize_f32(ay);
        let az = sanitize_f32(az);
        let dt = sanitize_f32(dt_sec).clamp(0.005, 0.20);

        let total_norm = (ax * ax + ay * ay + az * az).sqrt();

        // 10-40 Hz Road Vibration Energy (HPF)
        let alpha_hp = 0.85;
        self.vibration_high_pass =
            alpha_hp * (self.vibration_high_pass + total_norm - self.forward_accel_lp);
        self.forward_accel_lp += 0.15 * (total_norm - self.forward_accel_lp);
        let sample_vib_energy = self.vibration_high_pass.abs();
        self.vibration_energy_rms = self.vibration_energy_rms * 0.96 + sample_vib_energy * 0.04;
        self.vibration_energy_rms = sanitize_f32(self.vibration_energy_rms);

        if !is_gps_lost {
            // GPS is active; maintain baseline state
            return (self.smoothed_speed_kmh, SpeedSourceCode::Gps as i32);
        }

        // --- Tunnel Dead-Reckoning Mode ---
        let source: SpeedSourceCode;
        let alpha_speed: f32;
        if self.vibration_energy_rms < Self::VIBRATION_STOP_THRESHOLD {
            // Zero Velocity Update (ZUPT): Vehicle stationary inside tunnel
            self.estimated_speed_kmh = 0.0;
            source = SpeedSourceCode::Stopped;
            alpha_speed = 0.30; // Faster decay on confirmed full stop
        } else {
            // Vehicle in motion: Leaky integration toward anchor speed
            let leak_factor = (-dt / 20.0).exp(); // 20s time constant to suppress grade drift
            let target_anchor = 20.0_f32.max(self.last_gps_speed_kmh);
            self.estimated_speed_kmh =
                target_anchor * (1.0 - leak_factor) + self.estimated_speed_kmh * leak_factor;
            source = SpeedSourceCode::TunnelImu;
            alpha_speed = 0.18;
        }

        self.smoothed_speed_kmh += alpha_speed * (self.estimated_speed_kmh - self.smoothed_speed_kmh);
        self.smoothed_speed_kmh = sanitize_f32(self.smoothed_speed_kmh).max(0.0);

        (self.smoothed_speed_kmh, source as i32)
    }

    /// Computes cabin noise compensation intensity (0.0 .. 2.0) given current speed,
    /// reference cruising speed (km/h), and user compensation factor.
    ///
    /// At `speed == speed_ref`:
    /// - Standard factor (`1.0`) produces `1.0` (100%)
    /// - Maximum factor (`2.0`) produces `2.0` (200%)
    #[inline]
    pub fn calculate_intensity(
        speed_kmh: f32,
        speed_ref_kmh: f32,
        compensation_factor: f32,
    ) -> f32 {
        let valid_ref = sanitize_f32(speed_ref_kmh).clamp(40.0, 150.0);
        let norm_ratio = (sanitize_f32(speed_kmh).max(0.0) / valid_ref).clamp(0.0, 1.0);
        let scaled = norm_ratio * sanitize_f32(compensation_factor).clamp(0.2, 2.0);
        scaled.clamp(0.0, 2.0)
    }

    /// Computes real-time acoustic adjustments based on cabin intensity (0.0 .. 2.0):
    /// Returns `(punch_db, clarity_db, gain_trim_db)`.
    #[inline]
    pub fn calculate_acoustic_boosts(intensity: f32) -> (f32, f32, f32) {
        let i = sanitize_f32(intensity).clamp(0.0, 2.0);
        let punch_db = 1.0 + i * 3.6;          // +1.0 (idle) -> +4.6 dB (std) -> +8.2 dB (max) at 95 Hz
        let clarity_db = (i * 6.8).clamp(0.0, 10.5); // +0.0 -> +6.8 dB (std) -> +10.5 dB max at 2.6 kHz
        let gain_trim_db = i * 4.4;            // +0.0 (idle) -> +4.4 dB (std) -> +8.8 dB (max) speed trim
        (punch_db, clarity_db, gain_trim_db)
    }

    #[inline]
    pub fn smoothed_speed_kmh(&self) -> f32 {
        self.smoothed_speed_kmh
    }

    #[inline]
    pub fn vibration_energy_rms(&self) -> f32 {
        self.vibration_energy_rms
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vehicle_tracker_gps_speed_smoothing() {
        let mut tracker = VehicleTrackerEngine::new();
        let (speed1, src1) = tracker.on_gps_speed(100.0);
        assert_eq!(src1, SpeedSourceCode::Gps as i32);
        assert!(speed1 > 10.0 && speed1 < 30.0); // 0.18 * 100 = 18.0

        for _ in 0..30 {
            tracker.on_gps_speed(100.0);
        }
        assert!((tracker.smoothed_speed_kmh() - 100.0).abs() < 1.0);
    }

    #[test]
    fn test_vehicle_tracker_tunnel_dead_reckoning_and_zupt() {
        let mut tracker = VehicleTrackerEngine::new();
        // Set initial cruising speed
        for _ in 0..25 {
            tracker.on_gps_speed(80.0);
        }

        // Enter tunnel (GPS lost, road vibration present: e.g. 1.2 m/s^2 oscillation)
        let mut tunnel_speed = 0.0;
        let mut tunnel_src = 0;
        for i in 0..50 {
            let t = (i as f32) * 0.05;
            let vib = (t * 25.0).sin() * 1.5;
            let (spd, src) = tracker.on_sensor_accelerometer(0.1, vib, 9.8, 0.05, true);
            tunnel_speed = spd;
            tunnel_src = src;
        }
        assert_eq!(tunnel_src, SpeedSourceCode::TunnelImu as i32);
        assert!(tunnel_speed > 40.0 && tunnel_speed <= 85.0);

        // Vehicle stops at red light / traffic inside tunnel (vibration ceases)
        let mut stop_src = 0;
        for _ in 0..150 {
            let (_, src) = tracker.on_sensor_accelerometer(0.0, 0.0, 9.8, 0.05, true);
            stop_src = src;
        }
        assert_eq!(stop_src, SpeedSourceCode::Stopped as i32);
        assert!(tracker.smoothed_speed_kmh() < 1.0);
    }

    #[test]
    fn test_intensity_and_acoustic_boosts() {
        let intensity = VehicleTrackerEngine::calculate_intensity(80.0, 80.0, 1.0);
        assert!((intensity - 1.0).abs() < 1e-4);

        let max_intensity = VehicleTrackerEngine::calculate_intensity(80.0, 80.0, 2.0);
        assert!((max_intensity - 2.0).abs() < 1e-4);

        let half_intensity = VehicleTrackerEngine::calculate_intensity(40.0, 80.0, 1.0);
        assert!((half_intensity - 0.5).abs() < 1e-4);

        // Standard (1.0) boosts
        let (punch_std, clarity_std, trim_std) = VehicleTrackerEngine::calculate_acoustic_boosts(intensity);
        assert!((punch_std - 4.6).abs() < 1e-4);
        assert!((clarity_std - 6.8).abs() < 1e-4);
        assert!((trim_std - 4.4).abs() < 1e-4);

        // Max (2.0) boosts
        let (punch_max, clarity_max, trim_max) = VehicleTrackerEngine::calculate_acoustic_boosts(max_intensity);
        assert!((punch_max - 8.2).abs() < 1e-4);
        assert!((clarity_max - 10.5).abs() < 1e-4);
        assert!((trim_max - 8.8).abs() < 1e-4);
    }
}
