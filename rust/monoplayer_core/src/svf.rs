//! Zero-Delay Feedback Linear Trapezoidal State Variable Filter (Cytomic TPT SVF).
//! Unconditionally stable under fast gain/frequency modulation with bit-exact unity at 0 dB
//! and automatic subnormal/NaN state self-healing.

use crate::math_guard::sanitize_f32;
use std::f64::consts::PI;

#[derive(Clone, Copy, Debug)]
pub struct TptSvfFilter {
    ic1eq_l: f32,
    ic2eq_l: f32,
    ic1eq_r: f32,
    ic2eq_r: f32,
    a1: f32,
    a2: f32,
    a3: f32,
    m0: f32,
    m1: f32,
    m2: f32,
    is_bypass: bool,
}

impl Default for TptSvfFilter {
    fn default() -> Self {
        Self::new()
    }
}

impl TptSvfFilter {
    pub const fn new() -> Self {
        Self {
            ic1eq_l: 0.0,
            ic2eq_l: 0.0,
            ic1eq_r: 0.0,
            ic2eq_r: 0.0,
            a1: 0.0,
            a2: 0.0,
            a3: 0.0,
            m0: 1.0,
            m1: 0.0,
            m2: 0.0,
            is_bypass: true,
        }
    }

    #[inline]
    pub fn reset(&mut self) {
        self.ic1eq_l = 0.0;
        self.ic2eq_l = 0.0;
        self.ic1eq_r = 0.0;
        self.ic2eq_r = 0.0;
    }

    pub fn set_high_pass(&mut self, sample_rate: i32, freq_hz: f32, q: f32) {
        self.is_bypass = false;
        let sr = sample_rate.max(8000) as f32;
        let fc = freq_hz.clamp(15.0, sr * 0.45);
        let g = (PI * (fc as f64) / (sr as f64)).tan() as f32;
        let k = 1.0 / q.max(0.1);
        self.a1 = 1.0 / (1.0 + g * (g + k));
        self.a2 = g * self.a1;
        self.a3 = g * self.a2;
        self.m0 = 1.0;
        self.m1 = -k;
        self.m2 = -1.0;
    }

    pub fn set_low_pass(&mut self, sample_rate: i32, freq_hz: f32, q: f32) {
        self.is_bypass = false;
        let sr = sample_rate.max(8000) as f32;
        let fc = freq_hz.clamp(20.0, sr * 0.45);
        let g = (PI * (fc as f64) / (sr as f64)).tan() as f32;
        let k = 1.0 / q.max(0.1);
        self.a1 = 1.0 / (1.0 + g * (g + k));
        self.a2 = g * self.a1;
        self.a3 = g * self.a2;
        self.m0 = 0.0;
        self.m1 = 0.0;
        self.m2 = 1.0;
    }

    pub fn set_band_pass(&mut self, sample_rate: i32, freq_hz: f32, q: f32) {
        self.is_bypass = false;
        let sr = sample_rate.max(8000) as f32;
        let fc = freq_hz.clamp(20.0, sr * 0.45);
        let g = (PI * (fc as f64) / (sr as f64)).tan() as f32;
        let k = 1.0 / q.max(0.1);
        self.a1 = 1.0 / (1.0 + g * (g + k));
        self.a2 = g * self.a1;
        self.a3 = g * self.a2;
        self.m0 = 0.0;
        self.m1 = 1.0;
        self.m2 = 0.0;
    }

    pub fn set_bell(&mut self, sample_rate: i32, freq_hz: f32, q: f32, gain_db: f32) {
        if gain_db.abs() < 0.01 || !gain_db.is_finite() {
            self.is_bypass = true;
            return;
        }
        self.is_bypass = false;
        let sr = sample_rate.max(8000) as f32;
        let fc = freq_hz.clamp(20.0, sr * 0.45);
        let a = 10.0_f64.powf((gain_db as f64) / 40.0) as f32;
        let g = (PI * (fc as f64) / (sr as f64)).tan() as f32;
        let k = 1.0 / (q.max(0.2) * a);
        self.a1 = 1.0 / (1.0 + g * (g + k));
        self.a2 = g * self.a1;
        self.a3 = g * self.a2;
        self.m0 = 1.0;
        self.m1 = k * (a * a - 1.0);
        self.m2 = 0.0;
    }

    pub fn set_low_shelf(&mut self, sample_rate: i32, freq_hz: f32, q: f32, gain_db: f32) {
        if gain_db.abs() < 0.01 || !gain_db.is_finite() {
            self.is_bypass = true;
            return;
        }
        self.is_bypass = false;
        let sr = sample_rate.max(8000) as f32;
        let fc = freq_hz.clamp(20.0, sr * 0.45);
        let a = 10.0_f64.powf((gain_db as f64) / 40.0);
        let g = ((PI * (fc as f64) / (sr as f64)).tan() / a.sqrt()) as f32;
        let af = a as f32;
        let k = 1.0 / q.max(0.2);
        self.a1 = 1.0 / (1.0 + g * (g + k));
        self.a2 = g * self.a1;
        self.a3 = g * self.a2;
        self.m0 = 1.0;
        self.m1 = k * (af - 1.0);
        self.m2 = af * af - 1.0;
    }

    pub fn set_high_shelf(&mut self, sample_rate: i32, freq_hz: f32, q: f32, gain_db: f32) {
        if gain_db.abs() < 0.01 || !gain_db.is_finite() {
            self.is_bypass = true;
            return;
        }
        self.is_bypass = false;
        let sr = sample_rate.max(8000) as f32;
        let fc = freq_hz.clamp(20.0, sr * 0.45);
        let a = 10.0_f64.powf((gain_db as f64) / 40.0);
        let g = ((PI * (fc as f64) / (sr as f64)).tan() * a.sqrt()) as f32;
        let af = a as f32;
        let k = 1.0 / q.max(0.2);
        self.a1 = 1.0 / (1.0 + g * (g + k));
        self.a2 = g * self.a1;
        self.a3 = g * self.a2;
        self.m0 = af * af;
        self.m1 = k * (1.0 - af) * af;
        self.m2 = 1.0 - af * af;
    }

    #[inline(always)]
    pub fn process_mono(&mut self, v0: f32) -> f32 {
        let x = sanitize_f32(v0);
        if self.is_bypass {
            return x;
        }
        let v3 = x - self.ic2eq_l;
        let v1 = self.a1 * self.ic1eq_l + self.a2 * v3;
        let v2 = self.ic2eq_l + self.a2 * self.ic1eq_l + self.a3 * v3;
        self.ic1eq_l = sanitize_f32(2.0 * v1 - self.ic1eq_l);
        self.ic2eq_l = sanitize_f32(2.0 * v2 - self.ic2eq_l);
        sanitize_f32(self.m0 * x + self.m1 * v1 + self.m2 * v2)
    }

    #[inline(always)]
    pub fn process_right(&mut self, v0: f32) -> f32 {
        let x = sanitize_f32(v0);
        if self.is_bypass {
            return x;
        }
        let v3 = x - self.ic2eq_r;
        let v1 = self.a1 * self.ic1eq_r + self.a2 * v3;
        let v2 = self.ic2eq_r + self.a2 * self.ic1eq_r + self.a3 * v3;
        self.ic1eq_r = sanitize_f32(2.0 * v1 - self.ic1eq_r);
        self.ic2eq_r = sanitize_f32(2.0 * v2 - self.ic2eq_r);
        sanitize_f32(self.m0 * x + self.m1 * v1 + self.m2 * v2)
    }
}
