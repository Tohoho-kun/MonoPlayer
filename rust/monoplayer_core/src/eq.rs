//! 10-Band Peaking Biquad Equalizer (31.25Hz..16kHz) in Rust with Direct Form II Transposed
//! and subnormal/NaN state protection.

use crate::math_guard::sanitize_f32;
use std::f64::consts::PI;

pub const BAND_FREQUENCIES: [f32; 10] = [
    31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
];

pub struct TenBandEqEngine {
    sample_rate: i32,
    band_gains_db: [f32; 10],
    b0: [f32; 10],
    b1: [f32; 10],
    b2: [f32; 10],
    a1: [f32; 10],
    a2: [f32; 10],
    z1_l: [f32; 10],
    z2_l: [f32; 10],
    z1_r: [f32; 10],
    z2_r: [f32; 10],
}

impl TenBandEqEngine {
    pub fn new(sample_rate: i32) -> Self {
        let mut engine = Self {
            sample_rate: sample_rate.max(8000),
            band_gains_db: [0.0; 10],
            b0: [1.0; 10],
            b1: [0.0; 10],
            b2: [0.0; 10],
            a1: [0.0; 10],
            a2: [0.0; 10],
            z1_l: [0.0; 10],
            z2_l: [0.0; 10],
            z1_r: [0.0; 10],
            z2_r: [0.0; 10],
        };
        engine.recalculate_all();
        engine
    }

    pub fn set_sample_rate(&mut self, sr: i32) {
        let valid = sr.max(8000);
        if valid != self.sample_rate {
            self.sample_rate = valid;
            self.recalculate_all();
            self.reset_state();
        }
    }

    pub fn set_band_gain(&mut self, band_idx: usize, gain_db: f32) {
        if band_idx < 10 {
            let safe_db = if gain_db.is_finite() {
                gain_db.clamp(-12.0, 12.0)
            } else {
                0.0
            };
            self.band_gains_db[band_idx] = safe_db;
            self.recalculate_band(band_idx);
        }
    }

    pub fn set_all_bands(&mut self, gains: &[f32]) {
        let n = gains.len().min(10);
        for i in 0..n {
            let g = gains[i];
            self.band_gains_db[i] = if g.is_finite() { g.clamp(-12.0, 12.0) } else { 0.0 };
        }
        self.recalculate_all();
    }

    pub fn reset_state(&mut self) {
        self.z1_l.fill(0.0);
        self.z2_l.fill(0.0);
        self.z1_r.fill(0.0);
        self.z2_r.fill(0.0);
    }

    fn recalculate_all(&mut self) {
        for i in 0..10 {
            self.recalculate_band(i);
        }
    }

    fn recalculate_band(&mut self, i: usize) {
        let db = self.band_gains_db[i];
        if db.abs() < 0.01 {
            self.b0[i] = 1.0;
            self.b1[i] = 0.0;
            self.b2[i] = 0.0;
            self.a1[i] = 0.0;
            self.a2[i] = 0.0;
            return;
        }
        let sr = self.sample_rate as f64;
        let freq = (BAND_FREQUENCIES[i] as f64).min(sr * 0.45);
        let a = 10.0_f64.powf((db as f64) / 40.0);
        let w0 = 2.0 * PI * freq / sr;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let q = 1.4142135_f64;
        let alpha = sin_w0 / (2.0 * q);

        let raw_b0 = 1.0 + alpha * a;
        let raw_b1 = -2.0 * cos_w0;
        let raw_b2 = 1.0 - alpha * a;
        let raw_a0 = 1.0 + alpha / a;
        let raw_a1 = -2.0 * cos_w0;
        let raw_a2 = 1.0 - alpha / a;

        self.b0[i] = (raw_b0 / raw_a0) as f32;
        self.b1[i] = (raw_b1 / raw_a0) as f32;
        self.b2[i] = (raw_b2 / raw_a0) as f32;
        self.a1[i] = (raw_a1 / raw_a0) as f32;
        self.a2[i] = (raw_a2 / raw_a0) as f32;
    }

    #[inline(always)]
    pub fn process_mono_sample(&mut self, x: f32) -> f32 {
        let mut y = sanitize_f32(x);
        for i in 0..10 {
            let out = self.b0[i] * y + self.z1_l[i];
            self.z1_l[i] = sanitize_f32(self.b1[i] * y - self.a1[i] * out + self.z2_l[i]);
            self.z2_l[i] = sanitize_f32(self.b2[i] * y - self.a2[i] * out);
            y = sanitize_f32(out);
        }
        y
    }

    #[inline(always)]
    pub fn process_stereo_sample(&mut self, in_l: f32, in_r: f32) -> (f32, f32) {
        let mut y_l = sanitize_f32(in_l);
        let mut y_r = sanitize_f32(in_r);
        for i in 0..10 {
            let out_l = self.b0[i] * y_l + self.z1_l[i];
            self.z1_l[i] = sanitize_f32(self.b1[i] * y_l - self.a1[i] * out_l + self.z2_l[i]);
            self.z2_l[i] = sanitize_f32(self.b2[i] * y_l - self.a2[i] * out_l);
            y_l = sanitize_f32(out_l);

            let out_r = self.b0[i] * y_r + self.z1_r[i];
            self.z1_r[i] = sanitize_f32(self.b1[i] * y_r - self.a1[i] * out_r + self.z2_r[i]);
            self.z2_r[i] = sanitize_f32(self.b2[i] * y_r - self.a2[i] * out_r);
            y_r = sanitize_f32(out_r);
        }
        (y_l, y_r)
    }
}
