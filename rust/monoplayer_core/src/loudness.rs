//! ITU-R BS.1770-4 K-Weighting + Gated Dual-Horizon LUFS + 43ms Lookahead Peak Guard
//! + Noisy Car Cabin Road-Noise Masking Compensation in Rust.

use crate::math_guard::{sanitize_f32, sanitize_f64};
use crate::svf::TptSvfFilter;

const NUM_SHORT_TERM_BLOCKS: usize = 30;
const NUM_MOMENTARY_BLOCKS: usize = 4;
const GATE_FREEZE_LUFS: f32 = -50.0;
const PEAK_CEILING_LINEAR: f32 = 0.891;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LoudnessModeCode {
    Off = 0,
    NormalHifi = 1,
    NoisyCar = 2,
}

impl LoudnessModeCode {
    pub fn from_i32(v: i32) -> Self {
        match v {
            1 => Self::NormalHifi,
            2 => Self::NoisyCar,
            _ => Self::Off,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct LoudnessParams {
    pub mode: LoudnessModeCode,
    pub normal_target_lufs: f32,
    pub car_target_lufs: f32,
    pub car_cabin_intensity: f32,
}

pub struct LoudnessNormalizerEngine {
    sr: i32,
    k_stage1: TptSvfFilter,
    k_stage2: TptSvfFilter,
    car_subsonic_hp: TptSvfFilter,
    car_upper_bass_bell: TptSvfFilter,
    car_vocal_clarity_bell: TptSvfFilter,
    gated_blocks: [f32; NUM_SHORT_TERM_BLOCKS],
    gated_write_idx: usize,
    gated_count: usize,
    recent_blocks: [f32; NUM_MOMENTARY_BLOCKS],
    recent_write_idx: usize,
    recent_count: usize,
    block_energy_acc: f64,
    block_sample_count: usize,
    samples_per_sub_update: usize,
    slow_anchor_lufs: f32,
    anchor_initialised: bool,
    lookahead_peak_env: f32,
    post_eq_peak_env: f32,
    peak_release_coef: f32,
    target_gain_db: f32,
    current_gain_db: f32,
    current_linear_gain: f32,
    control_counter: i32,
    pub last_bass_punch_db: f32,
    pub measured_lufs: f32,
    pub applied_gain_db: f32,
    pub is_gate_frozen: bool,
    pub dynamic_clarity_boost_db: f32,
}

impl LoudnessNormalizerEngine {
    pub fn new(sample_rate: i32) -> Self {
        let valid_sr = sample_rate.max(8000);
        let mut engine = Self {
            sr: valid_sr,
            k_stage1: TptSvfFilter::new(),
            k_stage2: TptSvfFilter::new(),
            car_subsonic_hp: TptSvfFilter::new(),
            car_upper_bass_bell: TptSvfFilter::new(),
            car_vocal_clarity_bell: TptSvfFilter::new(),
            gated_blocks: [0.0; NUM_SHORT_TERM_BLOCKS],
            gated_write_idx: 0,
            gated_count: 0,
            recent_blocks: [0.0; NUM_MOMENTARY_BLOCKS],
            recent_write_idx: 0,
            recent_count: 0,
            block_energy_acc: 0.0,
            block_sample_count: 0,
            samples_per_sub_update: (valid_sr as usize / 20).max(256),
            slow_anchor_lufs: -16.0,
            anchor_initialised: false,
            lookahead_peak_env: 0.05,
            post_eq_peak_env: 0.05,
            peak_release_coef: 0.9995,
            target_gain_db: 0.0,
            current_gain_db: 0.0,
            current_linear_gain: 1.0,
            control_counter: 0,
            last_bass_punch_db: 0.0,
            measured_lufs: -24.0,
            applied_gain_db: 0.0,
            is_gate_frozen: true,
            dynamic_clarity_boost_db: 0.0,
        };
        engine.configure_filters(valid_sr);
        engine
    }

    pub fn set_sample_rate(&mut self, new_sr: i32) {
        let valid = new_sr.max(8000);
        if valid != self.sr {
            self.sr = valid;
            self.configure_filters(valid);
            self.reset_for_new_track();
        }
    }

    fn configure_filters(&mut self, sample_rate: i32) {
        self.k_stage1.set_high_shelf(sample_rate, 1681.97, 0.7071, 4.0);
        self.k_stage2.set_high_pass(sample_rate, 38.135, 0.5003);
        self.car_subsonic_hp.set_high_pass(sample_rate, 35.0, 0.7071);
        self.car_upper_bass_bell.set_bell(sample_rate, 95.0, 0.90, 2.0);
        self.car_vocal_clarity_bell.set_bell(sample_rate, 2600.0, 0.85, 0.0);
        self.peak_release_coef = (-1.0 / ((sample_rate as f32) * 0.085)).exp();
        self.samples_per_sub_update = ((sample_rate / 20) as usize).max(256);
    }

    pub fn reset_for_new_track(&mut self) {
        self.k_stage1.reset();
        self.k_stage2.reset();
        self.car_subsonic_hp.reset();
        self.car_upper_bass_bell.reset();
        self.car_vocal_clarity_bell.reset();
        self.gated_blocks.fill(0.0);
        self.recent_blocks.fill(0.0);
        self.gated_write_idx = 0;
        self.gated_count = 0;
        self.recent_write_idx = 0;
        self.recent_count = 0;
        self.block_energy_acc = 0.0;
        self.block_sample_count = 0;
        self.anchor_initialised = false;
        self.lookahead_peak_env = 0.05;
        self.post_eq_peak_env = 0.05;
        self.target_gain_db = 0.0;
        self.current_gain_db = 0.0;
        self.current_linear_gain = 1.0;
        self.applied_gain_db = 0.0;
        self.last_bass_punch_db = 0.0;
        self.dynamic_clarity_boost_db = 0.0;
        self.is_gate_frozen = true;
    }

    #[inline(always)]
    fn observe_post_eq_peak(&mut self, abs_peak: f32) {
        let p = sanitize_f32(abs_peak);
        if p > self.post_eq_peak_env {
            self.post_eq_peak_env = self.post_eq_peak_env * 0.25 + p * 0.75;
        } else {
            self.post_eq_peak_env = sanitize_f32(self.post_eq_peak_env * self.peak_release_coef);
        }
    }

    #[inline(always)]
    pub fn push_lookahead_sample(&mut self, raw_l: f32, raw_r: f32) {
        let abs_peak = sanitize_f32(raw_l).abs().max(sanitize_f32(raw_r).abs());
        self.lookahead_peak_env = if abs_peak > self.lookahead_peak_env {
            self.lookahead_peak_env * 0.15 + abs_peak * 0.85
        } else {
            sanitize_f32(self.lookahead_peak_env * self.peak_release_coef)
        };
    }

    pub fn process_mono_sample(&mut self, input: f32, params: &LoudnessParams) -> f32 {
        let x = sanitize_f32(input);
        self.observe_k_weighted_energy(x);
        if params.mode == LoudnessModeCode::Off {
            self.current_gain_db = sanitize_f32(self.current_gain_db * 0.995);
            self.current_linear_gain = 1.0;
            self.applied_gain_db = 0.0;
            self.dynamic_clarity_boost_db = 0.0;
            return x;
        }
        self.update_control_step_if_needed(params);
        let mut y = x * self.current_linear_gain;
        if params.mode == LoudnessModeCode::NoisyCar {
            y = self.car_subsonic_hp.process_mono(y);
            y = self.car_upper_bass_bell.process_mono(y);
            y = self.car_vocal_clarity_bell.process_mono(y);
        }
        let clean_y = sanitize_f32(y);
        self.observe_post_eq_peak(clean_y.abs());
        clean_y
    }

    pub fn process_stereo_sample(
        &mut self,
        in_l: f32,
        in_r: f32,
        params: &LoudnessParams,
    ) -> (f32, f32) {
        let xl = sanitize_f32(in_l);
        let xr = sanitize_f32(in_r);
        let power_proxy = (0.5 * (xl * xl + xr * xr)).sqrt();
        self.observe_k_weighted_energy(power_proxy);
        if params.mode == LoudnessModeCode::Off {
            self.current_gain_db = sanitize_f32(self.current_gain_db * 0.995);
            self.current_linear_gain = 1.0;
            self.applied_gain_db = 0.0;
            self.dynamic_clarity_boost_db = 0.0;
            return (xl, xr);
        }
        self.update_control_step_if_needed(params);
        let mut yl = xl * self.current_linear_gain;
        let mut yr = xr * self.current_linear_gain;
        if params.mode == LoudnessModeCode::NoisyCar {
            yl = self.car_subsonic_hp.process_mono(yl);
            yr = self.car_subsonic_hp.process_right(yr);
            yl = self.car_upper_bass_bell.process_mono(yl);
            yr = self.car_upper_bass_bell.process_right(yr);
            yl = self.car_vocal_clarity_bell.process_mono(yl);
            yr = self.car_vocal_clarity_bell.process_right(yr);
        }
        let cy_l = sanitize_f32(yl);
        let cy_r = sanitize_f32(yr);
        self.observe_post_eq_peak(cy_l.abs().max(cy_r.abs()));
        (cy_l, cy_r)
    }

    fn observe_k_weighted_energy(&mut self, sample: f32) {
        let kw = self.k_stage2.process_mono(self.k_stage1.process_mono(sample));
        self.block_energy_acc = sanitize_f64(self.block_energy_acc + (kw as f64) * (kw as f64));
        self.block_sample_count += 1;

        let target_block_samples = if self.gated_count < 6 {
            self.samples_per_sub_update
        } else {
            self.samples_per_sub_update * 2
        };

        if self.block_sample_count >= target_block_samples {
            let mean_sq = (self.block_energy_acc / (self.block_sample_count as f64)) as f32;
            self.block_energy_acc = 0.0;
            self.block_sample_count = 0;

            self.recent_blocks[self.recent_write_idx] = mean_sq;
            self.recent_write_idx = (self.recent_write_idx + 1) % NUM_MOMENTARY_BLOCKS;
            if self.recent_count < NUM_MOMENTARY_BLOCKS {
                self.recent_count += 1;
            }

            let mut mom_sum = 0.0_f64;
            for i in 0..self.recent_count {
                mom_sum += self.recent_blocks[i] as f64;
            }
            let mom_mean_sq = (mom_sum / (self.recent_count.max(1) as f64)) as f32;
            let mom_lufs = (-0.691 + 10.0 * mom_mean_sq.max(1.0e-10).log10()).clamp(-70.0, 3.0);
            let block_lufs = (-0.691 + 10.0 * mean_sq.max(1.0e-10).log10()).clamp(-70.0, 3.0);

            if block_lufs < GATE_FREEZE_LUFS || mom_lufs < GATE_FREEZE_LUFS {
                self.is_gate_frozen = true;
            } else {
                self.is_gate_frozen = false;
                self.gated_blocks[self.gated_write_idx] = mean_sq;
                self.gated_write_idx = (self.gated_write_idx + 1) % NUM_SHORT_TERM_BLOCKS;
                if self.gated_count < NUM_SHORT_TERM_BLOCKS {
                    self.gated_count += 1;
                }

                let mut ungated_sum = 0.0_f64;
                for i in 0..self.gated_count {
                    ungated_sum += self.gated_blocks[i] as f64;
                }
                let rel_threshold = ((ungated_sum / (self.gated_count as f64)) * 0.1) as f32;

                let mut gated_sum = 0.0_f64;
                let mut active_cnt = 0usize;
                for i in 0..self.gated_count {
                    let b = self.gated_blocks[i];
                    if b >= rel_threshold {
                        gated_sum += b as f64;
                        active_cnt += 1;
                    }
                }
                let st_mean_sq = if active_cnt > 0 {
                    (gated_sum / (active_cnt as f64)) as f32
                } else {
                    mom_mean_sq
                };
                let st_lufs = (-0.691 + 10.0 * st_mean_sq.max(1.0e-10).log10()).clamp(-65.0, 3.0);
                self.measured_lufs = st_lufs;

                if !self.anchor_initialised {
                    self.slow_anchor_lufs = st_lufs;
                    self.anchor_initialised = true;
                } else {
                    self.slow_anchor_lufs = self.slow_anchor_lufs * 0.985 + st_lufs * 0.015;
                }
            }
        }
    }

    fn update_control_step_if_needed(&mut self, params: &LoudnessParams) {
        self.control_counter += 1;
        if self.control_counter < 16 {
            return;
        }
        let dt_sec = (self.control_counter as f32) / (self.sr as f32);
        self.control_counter = 0;

        let intensity = sanitize_f32(params.car_cabin_intensity).clamp(0.0, 2.0);
        if !self.is_gate_frozen && self.anchor_initialised {
            let raw_desired_db = match params.mode {
                LoudnessModeCode::Off => 0.0,
                LoudnessModeCode::NormalHifi => {
                    let ref_lufs = self.slow_anchor_lufs * 0.70 + self.measured_lufs * 0.30;
                    (params.normal_target_lufs - ref_lufs).clamp(-12.0, 10.5)
                }
                LoudnessModeCode::NoisyCar => {
                    // Doubled speed-linked overall trim (+0.0 idle -> +4.4 std -> +8.8 max)
                    let speed_gain_db = 4.4 * intensity;
                    let base_deficit = params.car_target_lufs - self.measured_lufs;
                    let max_upward_boost = (6.5 + 15.0 * intensity).min(24.0);
                    if base_deficit > 0.0 {
                        let upward_ratio = (0.68 + 0.27 * intensity).clamp(0.68, 1.0);
                        ((base_deficit * upward_ratio).min(max_upward_boost) + speed_gain_db)
                            .min(26.0)
                    } else {
                        base_deficit.max(-18.0) + speed_gain_db
                    }
                }
            };
            self.target_gain_db = raw_desired_db;
        } else if self.is_gate_frozen {
            if self.current_gain_db >= 0.0 && self.target_gain_db > self.current_gain_db {
                self.target_gain_db = self.current_gain_db;
            }
            let relax_step = 0.20 * dt_sec;
            if self.target_gain_db > relax_step {
                self.target_gain_db -= relax_step;
            } else if self.target_gain_db < -relax_step {
                self.target_gain_db += relax_step;
            } else {
                self.target_gain_db = 0.0;
            }
        }

        // Reserve spectral headroom for NoisyCar bells so hot tracks don't slam post-EQ into clipping,
        // and feed back any post-EQ peak overshoot above -1.4 dBFS (0.85 linear).
        let eq_reserve_db = if params.mode == LoudnessModeCode::NoisyCar {
            let active_eq = (self.last_bass_punch_db * 0.42 + self.dynamic_clarity_boost_db * 0.22)
                .clamp(0.0, 5.2);
            let post_over_db = if self.post_eq_peak_env > 0.85 {
                (20.0 * (self.post_eq_peak_env / 0.85).log10()).clamp(0.0, 9.0)
            } else {
                0.0
            };
            active_eq.max(post_over_db)
        } else {
            0.0
        };

        let max_safe_gain_db = if self.lookahead_peak_env > 1.0e-4 {
            20.0 * (PEAK_CEILING_LINEAR / self.lookahead_peak_env).log10() - eq_reserve_db
        } else {
            18.0
        };
        let guarded_target_db = self.target_gain_db.min(max_safe_gain_db);

        let is_cold_start = (1..=8).contains(&self.gated_count);
        let tau_sec = if guarded_target_db < self.current_gain_db - 1.5
            && (guarded_target_db - max_safe_gain_db).abs() < 1.0e-4
        {
            0.020
        } else if is_cold_start {
            0.22
        } else if params.mode == LoudnessModeCode::NormalHifi {
            if guarded_target_db < self.current_gain_db { 0.40 } else { 2.20 }
        } else if guarded_target_db < self.current_gain_db {
            0.09
        } else {
            0.50
        };

        let alpha = (1.0 - (-dt_sec / tau_sec).exp()).clamp(0.0005, 1.0);
        self.current_gain_db = sanitize_f32(
            self.current_gain_db + alpha * (guarded_target_db - self.current_gain_db),
        );
        self.current_linear_gain = 10.0_f64.powf((self.current_gain_db as f64) / 20.0) as f32;
        self.applied_gain_db = self.current_gain_db;

        if params.mode == LoudnessModeCode::NoisyCar {
            // Softly taper bass/clarity boosts when post-EQ signal nears saturation so bass never overdrives
            let headroom_scale = if self.post_eq_peak_env > 0.86 {
                (1.0 - ((self.post_eq_peak_env - 0.86) / 0.45)).clamp(0.45, 1.0)
            } else {
                1.0
            };
            let bass_punch_db = (1.0 + 3.6 * intensity) * headroom_scale;
            self.last_bass_punch_db = bass_punch_db;
            self.car_upper_bass_bell.set_bell(self.sr, 95.0, 0.90, bass_punch_db);
            let quietness_factor = ((-8.0 - self.measured_lufs) / 24.0).clamp(0.25, 1.0);
            let clarity_db = (6.8 * intensity * quietness_factor).clamp(0.0, 10.5) * headroom_scale;
            self.dynamic_clarity_boost_db = clarity_db;
            self.car_vocal_clarity_bell.set_bell(self.sr, 2600.0, 0.85, clarity_db);
        } else {
            self.last_bass_punch_db = 0.0;
            self.dynamic_clarity_boost_db = 0.0;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_noisy_car_speed_gain_doubles_and_scales_linearly_with_headroom() {
        let sr = 48000;
        let run_sine = |intensity: f32| -> f32 {
            let mut eng = LoudnessNormalizerEngine::new(sr);
            let params = LoudnessParams {
                mode: LoudnessModeCode::NoisyCar,
                normal_target_lufs: -14.0,
                car_target_lufs: -19.0, // Headroom baseline
                car_cabin_intensity: intensity,
            };
            // -10 LUFS equivalent amplitude (~0.32 rms -> ~0.45 peak)
            let amp = 0.45_f32;
            for n in 0..(sr * 2) {
                let s = amp * (2.0 * std::f32::consts::PI * 1000.0 * (n as f32) / (sr as f32)).sin();
                eng.push_lookahead_sample(s, s);
                let _ = eng.process_mono_sample(s, &params);
            }
            eng.applied_gain_db
        };

        let gain_idle = run_sine(0.0);
        let gain_std = run_sine(1.0);
        let gain_max = run_sine(2.0);

        let delta_std = gain_std - gain_idle;
        let delta_max = gain_max - gain_idle;

        assert!(
            (delta_std - 4.4).abs() < 0.35,
            "Standard x1.0 speed gain delta should be ~+4.4 dB, got {delta_std} (idle={gain_idle}, std={gain_std})"
        );
        assert!(
            (delta_max - 8.8).abs() < 0.45,
            "Maximum x2.0 speed gain delta should be ~+8.8 dB without clipping guard crush, got {delta_max} (idle={gain_idle}, max={gain_max})"
        );
    }
}
