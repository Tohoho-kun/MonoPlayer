//! 3-Band Dynamic Equalizer (LOW Shelf / MID Peaking Bell / HIGH Shelf) in Rust.
//! Dedicated sidechain band detectors + 6dB Soft-Knee envelope followers +
//! Zero-Delay Feedback TPT SVFs modulated at 16-sample control rate.

use crate::math_guard::sanitize_f32;
use crate::svf::TptSvfFilter;

const KNEE_WIDTH_DB: f32 = 6.0;
const CONTROL_STEP_SAMPLES: i32 = 16;

#[derive(Clone, Copy, Debug)]
pub struct DynBandParams {
    pub enabled: bool,
    pub freq_hz: f32,
    pub q: f32,
    pub static_gain_db: f32,
    pub threshold_db: f32,
    pub max_dynamic_gain_db: f32,
    pub attack_ms: f32,
    pub release_ms: f32,
}

#[derive(Clone, Copy, Debug)]
pub struct DynamicEqParams {
    pub enabled: bool,
    pub bands: [DynBandParams; 3],
}

pub struct ThreeBandDynamicEqEngine {
    sr: i32,
    sc_detectors: [TptSvfFilter; 3],
    eq_stages: [TptSvfFilter; 3],
    sc_power_env: [f32; 3],
    current_dyn_delta_db: [f32; 3],
    last_detected_db: [f32; 3],
    last_low_freq: f32,
    last_mid_freq: f32,
    last_mid_q: f32,
    last_high_freq: f32,
    step_counter: i32,
}

impl ThreeBandDynamicEqEngine {
    pub fn new(sample_rate: i32) -> Self {
        let mut engine = Self {
            sr: sample_rate.max(8000),
            sc_detectors: [TptSvfFilter::new(); 3],
            eq_stages: [TptSvfFilter::new(); 3],
            sc_power_env: [1.0e-6; 3],
            current_dyn_delta_db: [0.0; 3],
            last_detected_db: [-60.0; 3],
            last_low_freq: -1.0,
            last_mid_freq: -1.0,
            last_mid_q: -1.0,
            last_high_freq: -1.0,
            step_counter: 0,
        };
        engine.reset();
        engine
    }

    pub fn set_sample_rate(&mut self, new_sr: i32) {
        let valid_sr = new_sr.max(8000);
        if valid_sr != self.sr {
            self.sr = valid_sr;
            self.last_low_freq = -1.0;
            self.last_mid_freq = -1.0;
            self.last_high_freq = -1.0;
            self.reset();
        }
    }

    pub fn reset(&mut self) {
        for i in 0..3 {
            self.sc_detectors[i].reset();
            self.eq_stages[i].reset();
            self.sc_power_env[i] = 1.0e-6;
            self.current_dyn_delta_db[i] = 0.0;
            self.last_detected_db[i] = -60.0;
        }
        self.step_counter = 0;
    }

    #[inline]
    pub fn detected_bands_db(&self) -> [f32; 3] {
        self.last_detected_db
    }

    #[inline]
    pub fn dynamic_gain_deltas_db(&self) -> [f32; 3] {
        self.current_dyn_delta_db
    }

    pub fn process_mono_sample(&mut self, input: f32, params: &DynamicEqParams) -> f32 {
        let x = sanitize_f32(input);
        if !params.enabled {
            for i in 0..3 {
                self.current_dyn_delta_db[i] = sanitize_f32(self.current_dyn_delta_db[i] * 0.98);
            }
            return x;
        }
        self.update_sidechain_and_filters(x, params);
        let mut y = x;
        if params.bands[0].enabled {
            y = self.eq_stages[0].process_mono(y);
        }
        if params.bands[1].enabled {
            y = self.eq_stages[1].process_mono(y);
        }
        if params.bands[2].enabled {
            y = self.eq_stages[2].process_mono(y);
        }
        sanitize_f32(y)
    }

    pub fn process_stereo_sample(
        &mut self,
        in_l: f32,
        in_r: f32,
        params: &DynamicEqParams,
    ) -> (f32, f32) {
        let xl = sanitize_f32(in_l);
        let xr = sanitize_f32(in_r);
        if !params.enabled {
            for i in 0..3 {
                self.current_dyn_delta_db[i] = sanitize_f32(self.current_dyn_delta_db[i] * 0.98);
            }
            return (xl, xr);
        }
        let sign = if xl + xr >= 0.0 { 1.0_f32 } else { -1.0_f32 };
        let mid_detector = (0.5 * (xl * xl + xr * xr)).sqrt() * sign;
        self.update_sidechain_and_filters(mid_detector, params);

        let mut yl = xl;
        let mut yr = xr;
        for i in 0..3 {
            if params.bands[i].enabled {
                yl = self.eq_stages[i].process_mono(yl);
                yr = self.eq_stages[i].process_right(yr);
            }
        }
        (sanitize_f32(yl), sanitize_f32(yr))
    }

    fn update_sidechain_and_filters(&mut self, detector_sample: f32, params: &DynamicEqParams) {
        let low = &params.bands[0];
        let mid = &params.bands[1];
        let high = &params.bands[2];

        if (low.freq_hz - self.last_low_freq).abs() > 1.0e-3 {
            self.last_low_freq = low.freq_hz;
            self.sc_detectors[0].set_low_pass(self.sr, low.freq_hz * 1.25, 0.7071);
        }
        if (mid.freq_hz - self.last_mid_freq).abs() > 1.0e-3 || (mid.q - self.last_mid_q).abs() > 1.0e-3 {
            self.last_mid_freq = mid.freq_hz;
            self.last_mid_q = mid.q;
            self.sc_detectors[1].set_band_pass(self.sr, mid.freq_hz, mid.q.clamp(0.4, 4.0));
        }
        if (high.freq_hz - self.last_high_freq).abs() > 1.0e-3 {
            self.last_high_freq = high.freq_hz;
            self.sc_detectors[2].set_high_pass(self.sr, high.freq_hz * 0.85, 0.7071);
        }

        let fast_env_alpha = 1.0 - (-1.0 / ((self.sr as f32) * 0.003)).exp();
        for i in 0..3 {
            let band_sig = self.sc_detectors[i].process_mono(detector_sample);
            let sq = band_sig * band_sig;
            self.sc_power_env[i] = sanitize_f32(
                self.sc_power_env[i] + fast_env_alpha * (sq - self.sc_power_env[i]),
            )
            .max(1.0e-10);
        }

        self.step_counter += 1;
        if self.step_counter < CONTROL_STEP_SAMPLES {
            return;
        }
        let dt_sec = (self.step_counter as f32) / (self.sr as f32);
        self.step_counter = 0;

        for i in 0..3 {
            let b = &params.bands[i];
            let env_db = (10.0 * self.sc_power_env[i].max(1.0e-10).log10()).clamp(-65.0, 6.0);
            self.last_detected_db[i] = env_db;

            let target_delta_db = if !b.enabled || b.max_dynamic_gain_db.abs() < 0.05 {
                0.0
            } else {
                compute_soft_knee_dynamic_gain(env_db, b.threshold_db, b.max_dynamic_gain_db)
            };

            let is_engaging = target_delta_db.abs() > self.current_dyn_delta_db[i].abs();
            let tau_sec = (if is_engaging { b.attack_ms } else { b.release_ms }).max(1.0) / 1000.0;
            let coef = (1.0 - (-dt_sec / tau_sec).exp()).clamp(0.001, 1.0);
            self.current_dyn_delta_db[i] = sanitize_f32(
                self.current_dyn_delta_db[i] + coef * (target_delta_db - self.current_dyn_delta_db[i]),
            );

            let total_gain_db = if b.enabled {
                (b.static_gain_db + self.current_dyn_delta_db[i]).clamp(-15.0, 15.0)
            } else {
                0.0
            };

            match i {
                0 => self.eq_stages[0].set_low_shelf(self.sr, b.freq_hz, 0.7071, total_gain_db),
                1 => self.eq_stages[1].set_bell(self.sr, b.freq_hz, b.q, total_gain_db),
                _ => self.eq_stages[2].set_high_shelf(self.sr, b.freq_hz, 0.7071, total_gain_db),
            }
        }
    }
}

pub fn compute_soft_knee_dynamic_gain(
    input_db: f32,
    threshold_db: f32,
    max_dynamic_gain_db: f32,
) -> f32 {
    let over = input_db - threshold_db;
    let half_knee = KNEE_WIDTH_DB * 0.5;
    let effective_over = if over <= -half_knee {
        0.0
    } else if over < half_knee {
        let x = over + half_knee;
        (x * x) / (2.0 * KNEE_WIDTH_DB)
    } else {
        over
    };
    if effective_over <= 0.0 {
        return 0.0;
    }
    let norm = effective_over / (effective_over + 9.0);
    let lo = 0.0_f32.min(max_dynamic_gain_db);
    let hi = 0.0_f32.max(max_dynamic_gain_db);
    (max_dynamic_gain_db * norm * 1.35).clamp(lo, hi)
}
