//! Numerical stability primitives: NaN/Infinity/Denormal flushing, continuous soft limiter,
//! and sessile droplet wetting-area power-law math.

#[inline(always)]
pub fn sanitize_f32(x: f32) -> f32 {
    if !x.is_finite() || x.abs() < 1.0e-20 {
        0.0
    } else {
        x
    }
}

#[inline(always)]
pub fn sanitize_f64(x: f64) -> f64 {
    if !x.is_finite() || x.abs() < 1.0e-30 {
        0.0
    } else {
        x
    }
}

/// Continuous soft limiter: linear up to knee (0.88), smooth tanh compression above,
/// bounded strictly within (-1.0, 1.0) with NaN/Inf protection.
#[inline(always)]
pub fn soft_limit(x: f32) -> f32 {
    let s = sanitize_f32(x);
    let knee = 0.88_f32;
    let a = s.abs();
    if a <= knee {
        return s;
    }
    let over = ((a - knee) / (1.0 - knee)) as f64;
    let y = knee + (1.0 - knee) * (over.tanh() as f32);
    if s < 0.0 {
        -y
    } else {
        y
    }
}

/// Stereo-linked smooth mastering peak limiter placed prior to final soft limiting / 16-bit quantization.
/// Uses fast envelope attack + slow 120ms release so overloaded low-bass + vocal mixtures are scaled
/// cleanly by a smooth scalar envelope instead of sample-by-sample square-wave distortion.
pub struct MasteringPeakLimiter {
    sr: i32,
    peak_env: f32,
    gain_smooth: f32,
    attack_coef: f32,
    release_coef: f32,
    pub last_gain_reduction_db: f32,
}

impl MasteringPeakLimiter {
    pub const THRESHOLD_LINEAR: f32 = 0.84; // -1.51 dBFS linear transparency threshold

    pub fn new(sample_rate: i32) -> Self {
        let mut eng = Self {
            sr: sample_rate.max(8000),
            peak_env: 0.0,
            gain_smooth: 1.0,
            attack_coef: 0.1,
            release_coef: 0.0002,
            last_gain_reduction_db: 0.0,
        };
        eng.configure_coefs();
        eng
    }

    pub fn set_sample_rate(&mut self, sample_rate: i32) {
        let valid = sample_rate.max(8000);
        if valid != self.sr {
            self.sr = valid;
            self.configure_coefs();
            self.reset();
        }
    }

    fn configure_coefs(&mut self) {
        let sr_f = self.sr as f32;
        self.attack_coef = (1.0 - (-1.0 / (sr_f * 0.00025)).exp()).clamp(0.01, 1.0);
        self.release_coef = (1.0 - (-1.0 / (sr_f * 0.120)).exp()).clamp(0.00002, 0.05);
    }

    pub fn reset(&mut self) {
        self.peak_env = 0.0;
        self.gain_smooth = 1.0;
        self.last_gain_reduction_db = 0.0;
    }

    #[inline(always)]
    pub fn process_stereo(&mut self, in_l: f32, in_r: f32) -> (f32, f32) {
        let xl = sanitize_f32(in_l);
        let xr = sanitize_f32(in_r);
        let abs_peak = xl.abs().max(xr.abs());

        if abs_peak > self.peak_env {
            // Instant peak capture prevents overshoot during sudden kicks/snare hits
            self.peak_env = abs_peak;
        } else {
            self.peak_env = sanitize_f32(self.peak_env + self.release_coef * (abs_peak - self.peak_env));
        }

        let target_gain = if self.peak_env > Self::THRESHOLD_LINEAR {
            (Self::THRESHOLD_LINEAR / self.peak_env).clamp(0.05, 1.0)
        } else {
            1.0
        };

        let coef = if target_gain < self.gain_smooth {
            self.attack_coef
        } else {
            self.release_coef
        };
        self.gain_smooth = sanitize_f32(self.gain_smooth + coef * (target_gain - self.gain_smooth))
            .clamp(0.05, 1.0);

        // Safety clamp against extreme > +12dBFS single-sample spikes without breaking 120ms release
        let min_safe_gain = if abs_peak > 0.94 {
            (0.94 / abs_peak).clamp(0.05, 1.0)
        } else {
            1.0
        };
        let eff_gain = self.gain_smooth.min(min_safe_gain);
        let yl = soft_limit(xl * eff_gain);
        let yr = soft_limit(xr * eff_gain);

        let out_peak = yl.abs().max(yr.abs());
        let total_gain = if abs_peak > 1e-5 {
            (out_peak / abs_peak).clamp(0.05, 1.0)
        } else {
            self.gain_smooth
        };
        self.last_gain_reduction_db = (-20.0 * total_gain.log10()).max(0.0);

        (yl, yr)
    }
}

/// Sessile droplet wetting-radius power law (p = 2.3) for realistic glass contact-area coalescence.
#[inline(always)]
pub fn combine_sessile_radius(r1: f32, r2: f32, factor: f32, max_r: f32) -> f32 {
    let v1 = (r1.max(0.0) as f64).powf(2.3);
    let v2 = (r2.max(0.0) as f64).powf(2.3) * (factor.max(0.0) as f64);
    ((v1 + v2).powf(1.0 / 2.3) as f32).min(max_r)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mastering_limiter_reports_gain_reduction_accurately() {
        let mut lim = MasteringPeakLimiter::new(48000);
        // Below threshold (-1.5 dBFS = 0.84): no gain reduction
        for _ in 0..200 {
            let (l, r) = lim.process_stereo(0.5, -0.5);
            assert!((l - 0.5).abs() < 1e-5);
            assert!((r + 0.5).abs() < 1e-5);
        }
        assert!(lim.last_gain_reduction_db < 0.01);

        // Hot boosted peaks (2.0 linear = +6 dBFS): prevented from clipping & GR > 5 dB
        for _ in 0..400 {
            let (l, r) = lim.process_stereo(2.0, 2.0);
            assert!(l.abs() <= 1.0);
            assert!(r.abs() <= 1.0);
        }
        assert!(lim.last_gain_reduction_db > 5.0);
    }
}
