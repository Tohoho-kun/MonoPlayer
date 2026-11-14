//! Native Vehicle Speed & Tunnel Dead-Reckoning Engine for MonoPlayer.
//!
//! Provides zero-allocation 3D mount-aware IMU physics:
//! - Gravity anchor (`g_anchor`) & horizontal acceleration projection (`a_h`)
//! - Straight-line GPS-assisted forward/lateral axis calibration
//! - Curve-immune forward-only dead-reckoning & braking deceleration
//! - Road vibration energy analysis & ZUPT (Zero Velocity Update) stop detection
//! - Handheld pickup posture detection & automatic steady-mount recovery
//! - Cabin noise acoustic adaptation calculations

use crate::math_guard::sanitize_f32;

/// Tracking source codes corresponding to Kotlin's `SpeedTrackingSource`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpeedSourceCode {
    Off = 0,
    Gps = 1,
    TunnelImu = 2,
    Stopped = 3,
    Handheld = 4,
}

/// Result of processing a 3D/9-axis IMU sample.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ImuSampleOutput {
    pub smoothed_speed_kmh: f32,
    pub source_code: i32,
    pub is_forward_calibrated: bool,
    pub a_fwd: f32,
    pub a_lat: f32,
}

#[derive(Debug, Clone, Copy, PartialEq)]
struct Vec3 {
    x: f32,
    y: f32,
    z: f32,
}

impl Vec3 {
    #[inline]
    const fn zero() -> Self {
        Self {
            x: 0.0,
            y: 0.0,
            z: 0.0,
        }
    }

    #[inline]
    const fn new(x: f32, y: f32, z: f32) -> Self {
        Self { x, y, z }
    }

    #[inline]
    fn sanitized(x: f32, y: f32, z: f32) -> Self {
        Self {
            x: sanitize_f32(x),
            y: sanitize_f32(y),
            z: sanitize_f32(z),
        }
    }

    #[inline]
    fn dot(self, other: Self) -> f32 {
        self.x * other.x + self.y * other.y + self.z * other.z
    }

    #[inline]
    fn cross(self, other: Self) -> Self {
        Self {
            x: self.y * other.z - self.z * other.y,
            y: self.z * other.x - self.x * other.z,
            z: self.x * other.y - self.y * other.x,
        }
    }

    #[inline]
    fn add(self, other: Self) -> Self {
        Self {
            x: self.x + other.x,
            y: self.y + other.y,
            z: self.z + other.z,
        }
    }

    #[inline]
    fn sub(self, other: Self) -> Self {
        Self {
            x: self.x - other.x,
            y: self.y - other.y,
            z: self.z - other.z,
        }
    }

    #[inline]
    fn scale(self, s: f32) -> Self {
        Self {
            x: self.x * s,
            y: self.y * s,
            z: self.z * s,
        }
    }

    #[inline]
    fn norm(self) -> f32 {
        self.dot(self).sqrt()
    }

    #[inline]
    fn normalize_or(self, fallback: Self) -> Self {
        let n = self.norm();
        if n > 1e-6 {
            self.scale(1.0 / n)
        } else {
            fallback
        }
    }

    #[inline]
    fn angle_to(self, other: Self) -> f32 {
        self.dot(other).clamp(-1.0, 1.0).acos()
    }
}

pub struct VehicleTrackerEngine {
    last_gps_speed_kmh: f32,
    estimated_speed_kmh: f32,
    smoothed_speed_kmh: f32,
    vibration_high_pass: f32,
    forward_accel_lp: f32,
    vibration_energy_rms: f32,

    // 3D mount gravity anchor & vehicle frame axes
    has_g_anchor: bool,
    g_anchor: Vec3,
    prev_g_hat: Vec3,
    is_forward_calibrated: bool,
    forward_accum: Vec3,
    forward_axis: Vec3,
    lateral_axis: Vec3,

    // Straight-line GPS-assisted calibration window
    has_calib_gps: bool,
    calib_start_gps_mps: f32,
    calib_ah_sum: Vec3,
    calib_dt_sum: f32,
    calib_turn_dt: f32,

    // Handheld posture detection & steady recovery
    is_handheld: bool,
    handheld_tilt_timer: f32,
    handheld_rot_timer: f32,
    steady_recovery_timer: f32,
    steady_ref_g: Vec3,

    // Last projected horizontal components
    last_a_fwd: f32,
    last_a_lat: f32,
}

impl Default for VehicleTrackerEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl VehicleTrackerEngine {
    pub const VIBRATION_STOP_THRESHOLD: f32 = 0.08;
    pub const CALIB_MIN_WINDOW_SEC: f32 = 0.8;
    pub const CALIB_MAX_WINDOW_SEC: f32 = 4.0;
    pub const CALIB_MIN_DV_MPS: f32 = 0.65;
    pub const CALIB_MIN_ACCEL_NORM: f32 = 0.20;
    pub const CURVE_LAT_ACCEL_THRESHOLD: f32 = 0.65;
    pub const CURVE_YAW_RATE_THRESHOLD: f32 = 0.12;
    pub const FWD_ACCEL_DEADZONE: f32 = 0.15;
    pub const HANDHELD_TILT_ANGLE_RAD: f32 = 0.30;
    pub const HANDHELD_RECALIB_ANGLE_RAD: f32 = 0.25;
    pub const HANDHELD_RECOVERY_SEC: f32 = 2.5;

    pub fn new() -> Self {
        let default_g = Vec3::new(0.0, 0.0, 1.0);
        Self {
            last_gps_speed_kmh: 0.0,
            estimated_speed_kmh: 0.0,
            smoothed_speed_kmh: 0.0,
            vibration_high_pass: 0.0,
            forward_accel_lp: 0.0,
            vibration_energy_rms: 0.0,
            has_g_anchor: false,
            g_anchor: default_g,
            prev_g_hat: default_g,
            is_forward_calibrated: false,
            forward_accum: Vec3::zero(),
            forward_axis: Vec3::zero(),
            lateral_axis: Vec3::zero(),
            has_calib_gps: false,
            calib_start_gps_mps: 0.0,
            calib_ah_sum: Vec3::zero(),
            calib_dt_sum: 0.0,
            calib_turn_dt: 0.0,
            is_handheld: false,
            handheld_tilt_timer: 0.0,
            handheld_rot_timer: 0.0,
            steady_recovery_timer: 0.0,
            steady_ref_g: default_g,
            last_a_fwd: 0.0,
            last_a_lat: 0.0,
        }
    }

    pub fn reset(&mut self) {
        *self = Self::new();
    }

    #[inline]
    fn reset_calib_window(&mut self, start_gps_mps: Option<f32>) {
        match start_gps_mps {
            Some(v) => {
                self.has_calib_gps = true;
                self.calib_start_gps_mps = v;
            }
            None => {
                self.has_calib_gps = false;
                self.calib_start_gps_mps = 0.0;
            }
        }
        self.calib_ah_sum = Vec3::zero();
        self.calib_dt_sum = 0.0;
        self.calib_turn_dt = 0.0;
    }

    #[inline]
    fn update_vibration_rms(&mut self, sample_norm: f32) {
        let alpha_hp = 0.85;
        self.vibration_high_pass =
            alpha_hp * (self.vibration_high_pass + sample_norm - self.forward_accel_lp);
        self.forward_accel_lp += 0.15 * (sample_norm - self.forward_accel_lp);
        let sample_vib_energy = self.vibration_high_pass.abs();
        self.vibration_energy_rms = self.vibration_energy_rms * 0.96 + sample_vib_energy * 0.04;
        self.vibration_energy_rms = sanitize_f32(self.vibration_energy_rms);
    }

    /// Handles a new GPS speed measurement (km/h).
    /// Returns the smoothed speed (km/h) and source code (`1 = Gps`, or `4 = Handheld` if handheld).
    pub fn on_gps_speed(&mut self, speed_kmh: f32) -> (f32, i32) {
        if self.is_handheld {
            self.reset_calib_window(None);
            return (0.0, SpeedSourceCode::Handheld as i32);
        }

        let valid_speed = sanitize_f32(speed_kmh).max(0.0);
        let speed_mps = valid_speed / 3.6;

        // Evaluate straight-line GPS-assisted forward axis calibration window
        if self.has_calib_gps {
            let dt_window = self.calib_dt_sum;
            let total_window = dt_window + self.calib_turn_dt;
            if total_window > Self::CALIB_MAX_WINDOW_SEC {
                self.reset_calib_window(Some(speed_mps));
            } else if dt_window >= Self::CALIB_MIN_WINDOW_SEC {
                let dv_mps = speed_mps - self.calib_start_gps_mps;
                let turn_fraction = self.calib_turn_dt / total_window.max(1e-3);
                if dv_mps.abs() >= Self::CALIB_MIN_DV_MPS && turn_fraction < 0.25 {
                    let avg_a_h = self.calib_ah_sum.scale(1.0 / dt_window);
                    if avg_a_h.norm() >= Self::CALIB_MIN_ACCEL_NORM {
                        let signed_a = avg_a_h.scale(dv_mps.signum());
                        self.forward_accum = self.forward_accum.scale(0.85).add(signed_a);
                        let g_ref = self.g_anchor;
                        let fwd_proj = self
                            .forward_accum
                            .sub(g_ref.scale(self.forward_accum.dot(g_ref)));
                        if fwd_proj.norm() >= 0.15 {
                            self.forward_axis = fwd_proj.normalize_or(Vec3::new(1.0, 0.0, 0.0));
                            self.lateral_axis = g_ref
                                .cross(self.forward_axis)
                                .normalize_or(Vec3::new(0.0, 1.0, 0.0));
                            self.is_forward_calibrated = true;
                        }
                    }
                }
                self.reset_calib_window(Some(speed_mps));
            }
        } else {
            self.reset_calib_window(Some(speed_mps));
        }

        self.last_gps_speed_kmh = valid_speed;
        self.estimated_speed_kmh = valid_speed;

        let alpha_speed = 0.25;
        self.smoothed_speed_kmh += alpha_speed * (valid_speed - self.smoothed_speed_kmh);
        self.smoothed_speed_kmh = sanitize_f32(self.smoothed_speed_kmh).max(0.0);

        (self.smoothed_speed_kmh, SpeedSourceCode::Gps as i32)
    }

    /// Processes a 3D / 9-axis IMU sample:
    /// - `(ax, ay, az)`: linear acceleration in m/s^2 (gravity removed)
    /// - `(gx, gy, gz)`: gravity vector in m/s^2
    /// - `(wx, wy, wz)`: gyroscope angular velocity in rad/s (if `has_gyro`)
    /// - `dt_sec`: elapsed time delta in seconds
    /// - `is_gps_lost`: true when GPS updates have timed out
    #[allow(clippy::too_many_arguments)]
    pub fn on_imu_sample(
        &mut self,
        ax: f32,
        ay: f32,
        az: f32,
        gx: f32,
        gy: f32,
        gz: f32,
        wx: f32,
        wy: f32,
        wz: f32,
        has_gyro: bool,
        dt_sec: f32,
        is_gps_lost: bool,
    ) -> ImuSampleOutput {
        let accel = Vec3::sanitized(ax, ay, az);
        let grav = Vec3::sanitized(gx, gy, gz);
        let gyro = Vec3::sanitized(wx, wy, wz);
        let dt = sanitize_f32(dt_sec).clamp(0.005, 0.20);

        let grav_norm = grav.norm();
        let g_hat = if grav_norm > 1.0 {
            grav.scale(1.0 / grav_norm)
        } else if self.has_g_anchor {
            self.g_anchor
        } else {
            Vec3::new(0.0, 0.0, 1.0)
        };

        if !self.has_g_anchor && grav_norm > 1.0 {
            self.has_g_anchor = true;
            self.g_anchor = g_hat;
            self.prev_g_hat = g_hat;
            self.steady_ref_g = g_hat;
        }

        // Compute posture rotation rates
        let dg_dt = g_hat.sub(self.prev_g_hat).norm() / dt;
        self.prev_g_hat = g_hat;

        let yaw_rate = if has_gyro { gyro.dot(g_hat) } else { 0.0 };
        let w_off_vertical = if has_gyro {
            gyro.sub(g_hat.scale(yaw_rate)).norm()
        } else {
            0.0
        };

        // Road vibration energy (HPF on linear acceleration magnitude)
        self.update_vibration_rms(accel.norm());

        // --- Handheld Posture Detection & Recovery ---
        if self.is_handheld {
            let ref_angle = g_hat.angle_to(self.steady_ref_g);
            let rock_steady =
                ref_angle < 0.08 && dg_dt < 0.20 && (!has_gyro || w_off_vertical < 0.20);
            if rock_steady {
                self.steady_recovery_timer += dt;
                if self.steady_recovery_timer >= Self::HANDHELD_RECOVERY_SEC {
                    let mount_change = g_hat.angle_to(self.g_anchor);
                    self.g_anchor = g_hat;
                    if mount_change > Self::HANDHELD_RECALIB_ANGLE_RAD {
                        self.is_forward_calibrated = false;
                        self.forward_accum = Vec3::zero();
                        self.forward_axis = Vec3::zero();
                        self.lateral_axis = Vec3::zero();
                    } else if self.is_forward_calibrated {
                        let proj = self
                            .forward_axis
                            .sub(self.g_anchor.scale(self.forward_axis.dot(self.g_anchor)));
                        if proj.norm() > 0.15 {
                            self.forward_axis = proj.normalize_or(Vec3::new(1.0, 0.0, 0.0));
                            self.lateral_axis = self
                                .g_anchor
                                .cross(self.forward_axis)
                                .normalize_or(Vec3::new(0.0, 1.0, 0.0));
                        } else {
                            self.is_forward_calibrated = false;
                            self.forward_accum = Vec3::zero();
                        }
                    }
                    self.is_handheld = false;
                    self.handheld_tilt_timer = 0.0;
                    self.handheld_rot_timer = 0.0;
                    self.steady_recovery_timer = 0.0;
                    self.reset_calib_window(None);
                }
            } else {
                self.steady_recovery_timer = (self.steady_recovery_timer - dt * 1.5).max(0.0);
                if ref_angle >= 0.12 || dg_dt >= 0.35 {
                    self.steady_ref_g = g_hat;
                }
            }

            if self.is_handheld {
                self.estimated_speed_kmh = 0.0;
                self.smoothed_speed_kmh = 0.0;
                self.last_a_fwd = 0.0;
                self.last_a_lat = 0.0;
                return ImuSampleOutput {
                    smoothed_speed_kmh: 0.0,
                    source_code: SpeedSourceCode::Handheld as i32,
                    is_forward_calibrated: self.is_forward_calibrated,
                    a_fwd: 0.0,
                    a_lat: 0.0,
                };
            }
        } else if self.has_g_anchor {
            // Stricter bump guard at higher vehicle speeds (and extra margin for mount touches while moving)
            let high_speed_factor = ((self.smoothed_speed_kmh - 30.0) / 50.0).clamp(0.0, 1.0);
            let moving_boost = if self.smoothed_speed_kmh > 15.0 {
                0.15 * high_speed_factor
            } else {
                0.0
            };
            let tilt_angle = g_hat.angle_to(self.g_anchor);
            let tilt_sustain_req = 0.25 + 0.35 * high_speed_factor + moving_boost;

            if tilt_angle > Self::HANDHELD_TILT_ANGLE_RAD {
                self.handheld_tilt_timer += dt;
            } else {
                self.handheld_tilt_timer = (self.handheld_tilt_timer - dt * 2.0).max(0.0);
            }

            let rot_rate = dg_dt.max(w_off_vertical);
            let rot_rate_thresh = 0.80 + 0.60 * high_speed_factor;
            let rot_sustain_req = 0.20 + 0.25 * high_speed_factor + moving_boost;

            if rot_rate > rot_rate_thresh {
                self.handheld_rot_timer += dt;
            } else {
                self.handheld_rot_timer = (self.handheld_rot_timer - dt * 2.0).max(0.0);
            }

            if self.handheld_tilt_timer >= tilt_sustain_req
                || self.handheld_rot_timer >= rot_sustain_req
            {
                self.is_handheld = true;
                self.steady_ref_g = g_hat;
                self.steady_recovery_timer = 0.0;
                self.estimated_speed_kmh = 0.0;
                self.smoothed_speed_kmh = 0.0;
                self.last_a_fwd = 0.0;
                self.last_a_lat = 0.0;
                self.reset_calib_window(None);
                return ImuSampleOutput {
                    smoothed_speed_kmh: 0.0,
                    source_code: SpeedSourceCode::Handheld as i32,
                    is_forward_calibrated: self.is_forward_calibrated,
                    a_fwd: 0.0,
                    a_lat: 0.0,
                };
            }
        }

        // --- Horizontal Projection (removing vertical suspension bumps) ---
        let a_vert = accel.dot(g_hat);
        let a_h = accel.sub(g_hat.scale(a_vert));

        let (a_fwd, a_lat) = if self.is_forward_calibrated {
            // Re-orthogonalize against instantaneous g_hat for road grade consistency
            let fwd_inst = self
                .forward_axis
                .sub(g_hat.scale(self.forward_axis.dot(g_hat)))
                .normalize_or(self.forward_axis);
            let lat_inst = g_hat.cross(fwd_inst).normalize_or(self.lateral_axis);
            (a_h.dot(fwd_inst), a_h.dot(lat_inst))
        } else {
            (0.0, 0.0)
        };
        self.last_a_fwd = a_fwd;
        self.last_a_lat = a_lat;

        // --- Curve Detection ---
        let is_turning = (self.is_forward_calibrated
            && a_lat.abs() > Self::CURVE_LAT_ACCEL_THRESHOLD)
            || (has_gyro && yaw_rate.abs() > Self::CURVE_YAW_RATE_THRESHOLD);

        // Accumulate horizontal accel during straight GPS-active driving for forward calibration
        if !is_gps_lost && self.has_calib_gps {
            if is_turning {
                self.calib_turn_dt += dt;
            } else {
                self.calib_ah_sum = self.calib_ah_sum.add(a_h.scale(dt));
                self.calib_dt_sum += dt;
            }
        } else if is_gps_lost {
            // Clear stale GPS calibration baseline if GPS is lost
            self.reset_calib_window(None);
        }

        if !is_gps_lost {
            return ImuSampleOutput {
                smoothed_speed_kmh: self.smoothed_speed_kmh,
                source_code: SpeedSourceCode::Gps as i32,
                is_forward_calibrated: self.is_forward_calibrated,
                a_fwd,
                a_lat,
            };
        }

        // --- Tunnel Dead-Reckoning Mode ---
        let a_fwd_eff = if self.is_forward_calibrated && a_fwd.abs() > Self::FWD_ACCEL_DEADZONE {
            a_fwd
        } else {
            0.0
        };

        let source: SpeedSourceCode;
        let alpha_speed: f32;

        // Prevent ZUPT false-stop while cornering or actively accelerating/braking
        if !is_turning
            && a_fwd_eff.abs() < 0.05
            && self.vibration_energy_rms < Self::VIBRATION_STOP_THRESHOLD
        {
            self.estimated_speed_kmh = 0.0;
            source = SpeedSourceCode::Stopped;
            alpha_speed = 0.30;
        } else {
            if self.is_forward_calibrated {
                // Integrate ONLY longitudinal forward acceleration (curve-immune)
                let dv_kmh = a_fwd_eff * dt * 3.6;
                self.estimated_speed_kmh = (self.estimated_speed_kmh + dv_kmh).clamp(0.0, 220.0);
            } else {
                // Fallback before forward axis calibration: leaky anchor toward last known speed
                let leak_factor = (-dt / 20.0).exp();
                let target_anchor = 20.0_f32.max(self.last_gps_speed_kmh);
                self.estimated_speed_kmh =
                    target_anchor * (1.0 - leak_factor) + self.estimated_speed_kmh * leak_factor;
            }
            source = SpeedSourceCode::TunnelImu;
            alpha_speed = 0.18;
        }

        self.smoothed_speed_kmh += alpha_speed * (self.estimated_speed_kmh - self.smoothed_speed_kmh);
        self.smoothed_speed_kmh = sanitize_f32(self.smoothed_speed_kmh).max(0.0);

        ImuSampleOutput {
            smoothed_speed_kmh: self.smoothed_speed_kmh,
            source_code: source as i32,
            is_forward_calibrated: self.is_forward_calibrated,
            a_fwd,
            a_lat,
        }
    }

    /// Backwards-compatible 3-axis accelerometer dead-reckoning handler.
    /// Returns `(smoothed_speed_kmh, source_code)`.
    pub fn on_sensor_accelerometer(
        &mut self,
        ax: f32,
        ay: f32,
        az: f32,
        dt_sec: f32,
        is_gps_lost: bool,
    ) -> (f32, i32) {
        if self.is_handheld {
            return (0.0, SpeedSourceCode::Handheld as i32);
        }

        let ax = sanitize_f32(ax);
        let ay = sanitize_f32(ay);
        let az = sanitize_f32(az);
        let dt = sanitize_f32(dt_sec).clamp(0.005, 0.20);

        let total_norm = (ax * ax + ay * ay + az * az).sqrt();
        self.update_vibration_rms(total_norm);

        if !is_gps_lost {
            return (self.smoothed_speed_kmh, SpeedSourceCode::Gps as i32);
        }

        let source: SpeedSourceCode;
        let alpha_speed: f32;
        if self.vibration_energy_rms < Self::VIBRATION_STOP_THRESHOLD {
            self.estimated_speed_kmh = 0.0;
            source = SpeedSourceCode::Stopped;
            alpha_speed = 0.30;
        } else {
            let leak_factor = (-dt / 20.0).exp();
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

    #[inline]
    pub fn is_forward_calibrated(&self) -> bool {
        self.is_forward_calibrated
    }

    #[inline]
    pub fn is_handheld(&self) -> bool {
        self.is_handheld
    }

    #[inline]
    pub fn last_a_fwd(&self) -> f32 {
        self.last_a_fwd
    }

    #[inline]
    pub fn last_a_lat(&self) -> f32 {
        self.last_a_lat
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: rotate a 3D car-frame vector `(fwd, right, up)` into a tilted/rotated phone frame.
    /// Applies yaw `psi` around world Z, then pitch `theta` around X.
    fn car_to_phone(car: [f32; 3], yaw_rad: f32, pitch_rad: f32) -> [f32; 3] {
        let (sy, cy) = yaw_rad.sin_cos();
        let (sp, cp) = pitch_rad.sin_cos();
        // Yaw around Z
        let x1 = cy * car[0] + sy * car[1];
        let y1 = -sy * car[0] + cy * car[1];
        let z1 = car[2];
        // Pitch around X
        let x2 = x1;
        let y2 = cp * y1 + sp * z1;
        let z2 = -sp * y1 + cp * z1;
        [x2, y2, z2]
    }

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
        for _ in 0..25 {
            tracker.on_gps_speed(80.0);
        }

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

        let (punch_std, clarity_std, trim_std) =
            VehicleTrackerEngine::calculate_acoustic_boosts(intensity);
        assert!((punch_std - 4.6).abs() < 1e-4);
        assert!((clarity_std - 6.8).abs() < 1e-4);
        assert!((trim_std - 4.4).abs() < 1e-4);

        let (punch_max, clarity_max, trim_max) =
            VehicleTrackerEngine::calculate_acoustic_boosts(max_intensity);
        assert!((punch_max - 8.2).abs() < 1e-4);
        assert!((clarity_max - 10.5).abs() < 1e-4);
        assert!((trim_max - 8.8).abs() < 1e-4);
    }

    #[test]
    fn test_rotated_mount_straight_line_calibration_and_bump_rejection() {
        let mut tracker = VehicleTrackerEngine::new();
        let yaw = 0.85_f32; // ~49 deg rotated holder
        let pitch = 0.55_f32; // ~31.5 deg tilted holder
        let g_phone = car_to_phone([0.0, 0.0, 9.81], yaw, pitch);

        // Start at 20 km/h
        tracker.on_gps_speed(20.0);

        // Straight-line acceleration at +2.0 m/s^2 for 2.0 seconds WITH strong vertical road bumps
        let dt = 0.05;
        for i in 0..40 {
            let bump = ((i as f32) * 1.4).sin() * 3.5; // +/- 3.5 m/s^2 vertical bump
            let a_phone = car_to_phone([2.0, 0.0, bump], yaw, pitch);
            let out = tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_phone[0], g_phone[1], g_phone[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
            assert_eq!(out.source_code, SpeedSourceCode::Gps as i32);
        }

        // GPS speed increases by +4.0 m/s (+14.4 km/h -> 34.4 km/h)
        tracker.on_gps_speed(34.4);
        assert!(tracker.is_forward_calibrated());

        // Verify pure forward accel projects cleanly into a_fwd (~2.5) and a_lat (~0)
        // despite vertical suspension bump
        let a_test = car_to_phone([2.5, 0.0, -4.0], yaw, pitch);
        let out = tracker.on_imu_sample(
            a_test[0], a_test[1], a_test[2],
            g_phone[0], g_phone[1], g_phone[2],
            0.0, 0.0, 0.0, true, dt, false,
        );
        assert!(out.is_forward_calibrated);
        assert!((out.a_fwd - 2.5).abs() < 0.05, "a_fwd={}", out.a_fwd);
        assert!(out.a_lat.abs() < 0.05, "a_lat={}", out.a_lat);
    }

    #[test]
    fn test_tunnel_braking_deceleration_with_calibrated_axis() {
        let mut tracker = VehicleTrackerEngine::new();
        let yaw = -0.6_f32;
        let pitch = 0.4_f32;
        let g_phone = car_to_phone([0.0, 0.0, 9.81], yaw, pitch);
        let dt = 0.05;

        // Establish straight calibration from 36 km/h -> 50.4 km/h over 2s
        tracker.on_gps_speed(36.0);
        for _ in 0..40 {
            let a_phone = car_to_phone([2.0, 0.0, 0.0], yaw, pitch);
            tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_phone[0], g_phone[1], g_phone[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
        }
        for _ in 0..25 {
            tracker.on_gps_speed(72.0);
        }
        assert!(tracker.is_forward_calibrated());
        let initial_speed = tracker.smoothed_speed_kmh();
        assert!(initial_speed > 65.0);

        // Enter tunnel and brake hard at -3.0 m/s^2 for 3 seconds (-9 m/s = -32.4 km/h)
        let mut last_out = ImuSampleOutput {
            smoothed_speed_kmh: 0.0,
            source_code: 0,
            is_forward_calibrated: false,
            a_fwd: 0.0,
            a_lat: 0.0,
        };
        for i in 0..60 {
            let vib = ((i as f32) * 1.2).sin() * 0.4;
            let a_phone = car_to_phone([-3.0, 0.0, vib], yaw, pitch);
            last_out = tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_phone[0], g_phone[1], g_phone[2],
                0.0, 0.0, 0.0, true, dt, true,
            );
        }
        assert_eq!(last_out.source_code, SpeedSourceCode::TunnelImu as i32);
        assert!(
            last_out.smoothed_speed_kmh < initial_speed - 25.0,
            "expected substantial braking deceleration from {initial_speed}, got {}",
            last_out.smoothed_speed_kmh
        );
        assert!(last_out.smoothed_speed_kmh > 25.0);
    }

    #[test]
    fn test_curve_immunity_no_axis_corruption_no_zupt_no_speed_drift() {
        let mut tracker = VehicleTrackerEngine::new();
        let yaw = 0.35_f32;
        let pitch = 0.25_f32;
        let g_phone = car_to_phone([0.0, 0.0, 9.81], yaw, pitch);
        let w_turn = car_to_phone([0.0, 0.0, 0.35], yaw, pitch); // 0.35 rad/s pure yaw turn
        let dt = 0.05;

        // First calibrate straight line
        tracker.on_gps_speed(20.0);
        for _ in 0..40 {
            let a_phone = car_to_phone([2.0, 0.0, 0.0], yaw, pitch);
            tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_phone[0], g_phone[1], g_phone[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
        }
        for _ in 0..25 {
            tracker.on_gps_speed(60.0);
        }
        assert!(tracker.is_forward_calibrated());
        let saved_fwd = tracker.forward_axis;
        let pre_curve_speed = tracker.smoothed_speed_kmh();

        // Enter smooth curved tunnel ramp: strong lateral centripetal accel (3.5 m/s^2),
        // zero forward accel, smooth asphalt (zero vertical vibration)
        for _ in 0..80 {
            let a_phone = car_to_phone([0.0, 3.5, 0.0], yaw, pitch);
            let out = tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_phone[0], g_phone[1], g_phone[2],
                w_turn[0], w_turn[1], w_turn[2],
                true, dt, true,
            );
            // Must NOT falsely ZUPT stop during smooth cornering
            assert_eq!(out.source_code, SpeedSourceCode::TunnelImu as i32);
            // Speed must remain immune to centripetal force
            assert!(
                (out.smoothed_speed_kmh - pre_curve_speed).abs() < 1.0,
                "speed corrupted during turn: {} vs {}",
                out.smoothed_speed_kmh,
                pre_curve_speed
            );
        }

        // Also ensure GPS updates during a sharp turn don't corrupt forward_axis
        tracker.on_gps_speed(60.0);
        for _ in 0..40 {
            let a_phone = car_to_phone([0.0, 4.0, 0.0], yaw, pitch);
            tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_phone[0], g_phone[1], g_phone[2],
                w_turn[0], w_turn[1], w_turn[2],
                true, dt, false,
            );
        }
        tracker.on_gps_speed(65.0);
        let axis_shift = tracker.forward_axis.angle_to(saved_fwd);
        assert!(axis_shift < 0.05, "forward axis shifted by {axis_shift} rad during turn");
    }

    #[test]
    fn test_handheld_detection_gps_blocking_high_speed_bump_guard_and_recovery() {
        let mut tracker = VehicleTrackerEngine::new();
        let g_mount = car_to_phone([0.0, 0.0, 9.81], 0.0, 0.3);
        let dt = 0.05;

        // Drive at highway speed (~90 km/h) and calibrate straight axis
        tracker.on_gps_speed(75.0);
        for _ in 0..40 {
            let a_phone = car_to_phone([1.5, 0.0, 0.0], 0.0, 0.3);
            tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_mount[0], g_mount[1], g_mount[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
        }
        for _ in 0..30 {
            tracker.on_gps_speed(90.0);
        }
        assert!(tracker.is_forward_calibrated());
        assert!(!tracker.is_handheld());

        // Brief high-speed pothole/bump disturbance (2 samples = 0.10s) must NOT trigger Handheld
        let g_bump = car_to_phone([0.0, 0.0, 9.81], 0.0, 0.65);
        for _ in 0..2 {
            let out = tracker.on_imu_sample(
                0.0, 0.0, 4.0,
                g_bump[0], g_bump[1], g_bump[2],
                1.5, 0.0, 0.0, true, dt, false,
            );
            assert_ne!(out.source_code, SpeedSourceCode::Handheld as i32);
        }
        // Return to mount posture
        for _ in 0..10 {
            let out = tracker.on_imu_sample(
                0.0, 0.0, 0.0,
                g_mount[0], g_mount[1], g_mount[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
            assert_eq!(out.source_code, SpeedSourceCode::Gps as i32);
        }

        // Now user picks up phone (sustained tilt > 0.30 rad, e.g. 0.70 rad tilt for 0.8s)
        let g_hand = car_to_phone([0.0, 0.0, 9.81], 0.0, 1.0);
        let mut last_out = ImuSampleOutput {
            smoothed_speed_kmh: 99.0,
            source_code: 0,
            is_forward_calibrated: false,
            a_fwd: 0.0,
            a_lat: 0.0,
        };
        for _ in 0..20 {
            last_out = tracker.on_imu_sample(
                0.2, 0.1, 0.3,
                g_hand[0], g_hand[1], g_hand[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
        }
        assert!(tracker.is_handheld());
        assert_eq!(last_out.source_code, SpeedSourceCode::Handheld as i32);
        assert_eq!(last_out.smoothed_speed_kmh, 0.0);

        // While Handheld=true, GPS updates must be ignored and report speed=0, source=4
        let (gps_spd, gps_src) = tracker.on_gps_speed(85.0);
        assert_eq!(gps_spd, 0.0);
        assert_eq!(gps_src, SpeedSourceCode::Handheld as i32);

        // User places phone into a DIFFERENT mount angle (> 0.25 rad from old g_mount)
        // and keeps it rock-steady for ~2.6s -> auto recovery + forward calibration reset
        let g_new_mount = car_to_phone([0.0, 0.0, 9.81], 0.0, 0.75); // +0.45 rad change vs 0.30
        for _ in 0..45 {
            // 2.25s < 2.5s: still handheld
            let out = tracker.on_imu_sample(
                0.0, 0.0, 0.0,
                g_new_mount[0], g_new_mount[1], g_new_mount[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
            assert_eq!(out.source_code, SpeedSourceCode::Handheld as i32);
        }
        for _ in 0..10 {
            // crosses 2.5s steady threshold
            last_out = tracker.on_imu_sample(
                0.0, 0.0, 0.0,
                g_new_mount[0], g_new_mount[1], g_new_mount[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
        }
        assert!(!tracker.is_handheld());
        assert_eq!(last_out.source_code, SpeedSourceCode::Gps as i32);
        assert!(!tracker.is_forward_calibrated(), "expected forward calibration reset when mount angle changed > 0.25 rad");
    }

    #[test]
    fn test_handheld_gyro_rotation_trigger_and_same_mount_recovery_preserves_calibration() {
        let mut tracker = VehicleTrackerEngine::new();
        let g_mount = car_to_phone([0.0, 0.0, 9.81], 0.2, 0.35);
        let dt = 0.05;

        // Calibrate forward axis
        tracker.on_gps_speed(15.0);
        for _ in 0..30 {
            let a_phone = car_to_phone([2.0, 0.0, 0.0], 0.2, 0.35);
            tracker.on_imu_sample(
                a_phone[0], a_phone[1], a_phone[2],
                g_mount[0], g_mount[1], g_mount[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
        }
        tracker.on_gps_speed(30.0);
        assert!(tracker.is_forward_calibrated());

        // Trigger handheld via rapid off-vertical gyro rotation (pitch/roll rate 1.6 rad/s)
        let w_pitch = car_to_phone([1.6, 0.0, 0.0], 0.2, 0.35);
        for _ in 0..8 {
            tracker.on_imu_sample(
                0.0, 0.0, 0.0,
                g_mount[0], g_mount[1], g_mount[2],
                w_pitch[0], w_pitch[1], w_pitch[2], true, dt, false,
            );
        }
        assert!(tracker.is_handheld());

        // Place back into nearly the same mount angle (0.10 rad difference <= 0.25 rad)
        let g_same_mount = car_to_phone([0.0, 0.0, 9.81], 0.2, 0.45);
        for _ in 0..55 {
            tracker.on_imu_sample(
                0.0, 0.0, 0.0,
                g_same_mount[0], g_same_mount[1], g_same_mount[2],
                0.0, 0.0, 0.0, true, dt, false,
            );
        }
        assert!(!tracker.is_handheld());
        assert!(tracker.is_forward_calibrated(), "expected calibration preserved when mount angle changed <= 0.25 rad");
    }
}
