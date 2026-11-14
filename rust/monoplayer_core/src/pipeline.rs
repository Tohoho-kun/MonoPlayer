use crate::declip::{DeclipEngine, DeclipParams};
use crate::dynamic_eq::{DynamicEqParams, ThreeBandDynamicEqEngine};
use crate::eq::TenBandEqEngine;
use crate::loudness::{LoudnessNormalizerEngine, LoudnessParams};
use crate::spatial_depth::{SpatialParams, StereoDepthEngine};
use crate::stft_mono::StftMonoEngine;

const FFT_SIZE: usize = 2048;
const HOP_SIZE: usize = 512;
const RING_MASK: usize = FFT_SIZE - 1;
const OLA_SIZE: usize = FFT_SIZE * 2;
const OLA_MASK: usize = OLA_SIZE - 1;

/// Algorithm IDs matching Kotlin `MonoAlgorithm` ordinals.
pub const ALGO_HILBERT: i32 = 0;
pub const ALGO_STFT_ENERGY: i32 = 1;
pub const ALGO_PHASE_ALIGN: i32 = 2;
pub const ALGO_DYNAMIC_PCA: i32 = 3;
pub const ALGO_NAIVE: i32 = 4;
pub const ALGO_STEREO_REF: i32 = 5;

#[derive(Clone, Debug)]
pub struct PipelineParams {
    pub algo: i32,
    pub blend: f32,
    pub crossover_hz: f32,
    pub preamp_linear: f32,
    pub base_external_multiplier: f32,
    pub is_crossfade_active: bool,
    pub cf_sec: f32,
    pub eq_enabled: bool,
    pub eq_gains: [f32; 10],
    pub declip: DeclipParams,
    pub loudness: LoudnessParams,
    pub spatial: SpatialParams,
    pub dyn_eq: DynamicEqParams,
}

pub struct PipelineEngine {
    sample_rate: i32,
    pub declip: DeclipEngine,
    pub loud_norm: LoudnessNormalizerEngine,
    pub stft: StftMonoEngine,
    pub stereo_depth: StereoDepthEngine,
    pub dyn_eq: ThreeBandDynamicEqEngine,
    pub eq: TenBandEqEngine,

    in_ring_l: Vec<f32>,
    in_ring_r: Vec<f32>,
    ordered_l: Vec<f32>,
    ordered_r: Vec<f32>,
    win_time: Vec<f32>,
    win_sq: Vec<f32>,
    ola_out: Vec<f32>,
    ola_norm: Vec<f32>,
    vis_spec: [f32; 64],
    vis_naive: [f32; 64],
    stft_metrics: [f32; 4],
    ring_write_pos: usize,
    abs_count: i64,
    samples_since_hop: usize,

    // Dynamic PCA state
    lp_l: f32,
    lp_r: f32,
    cov_ll: f32,
    cov_rr: f32,
    pca_count: usize,
    pca_dominant_sign: f32,
    pca_sign_smooth: f32,

    // Gain smoothing
    smooth_gain: f32,
    gain_initialised: bool,

    // Mastering limiter
    lim_env: f32,

    // Telemetry accumulators
    win_pre_boost_peak: f32,
    win_post_boost_peak: f32,
    win_out_peak: f32,
    win_max_gr_db: f32,
    sum_sq_l: f64,
    sum_sq_r: f64,
    win_frames: usize,
}

impl PipelineEngine {
    pub fn new(sample_rate: i32) -> Self {
        let sr = sample_rate.max(8000);
        Self {
            sample_rate: sr,
            declip: DeclipEngine::new(sr),
            loud_norm: LoudnessNormalizerEngine::new(sr),
            stft: StftMonoEngine::new(sr as u32),
            stereo_depth: StereoDepthEngine::new(sr),
            dyn_eq: ThreeBandDynamicEqEngine::new(sr),
            eq: TenBandEqEngine::new(sr),
            in_ring_l: vec![0.0; FFT_SIZE],
            in_ring_r: vec![0.0; FFT_SIZE],
            ordered_l: vec![0.0; FFT_SIZE],
            ordered_r: vec![0.0; FFT_SIZE],
            win_time: vec![0.0; FFT_SIZE],
            win_sq: vec![0.0; FFT_SIZE],
            ola_out: vec![0.0; OLA_SIZE],
            ola_norm: vec![0.0; OLA_SIZE],
            vis_spec: [0.0; 64],
            vis_naive: [0.0; 64],
            stft_metrics: [0.0; 4],
            ring_write_pos: 0,
            abs_count: 0,
            samples_since_hop: 0,
            lp_l: 0.0,
            lp_r: 0.0,
            cov_ll: 0.0,
            cov_rr: 0.0,
            pca_count: 0,
            pca_dominant_sign: 1.0,
            pca_sign_smooth: 1.0,
            smooth_gain: 1.0,
            gain_initialised: false,
            lim_env: 0.0,
            win_pre_boost_peak: 0.0,
            win_post_boost_peak: 0.0,
            win_out_peak: 0.0,
            win_max_gr_db: 0.0,
            sum_sq_l: 0.0,
            sum_sq_r: 0.0,
            win_frames: 0,
        }
    }

    pub fn set_sample_rate(&mut self, sr: i32) {
        let sr = sr.max(8000);
        self.sample_rate = sr;
        self.declip.set_sample_rate(sr);
        self.loud_norm.set_sample_rate(sr);
        self.stft.set_sample_rate(sr as u32);
        self.stereo_depth.set_sample_rate(sr);
        self.dyn_eq.set_sample_rate(sr);
        self.eq.set_sample_rate(sr);
    }

    pub fn reset(&mut self) {
        self.declip.reset();
        self.loud_norm.reset_for_new_track();
        self.stft.reset();
        self.stereo_depth.reset();
        self.dyn_eq.reset();
        self.eq.reset_state();
        self.in_ring_l.fill(0.0);
        self.in_ring_r.fill(0.0);
        self.ola_out.fill(0.0);
        self.ola_norm.fill(0.0);
        self.ring_write_pos = 0;
        self.abs_count = 0;
        self.samples_since_hop = 0;
        self.lp_l = 0.0;
        self.lp_r = 0.0;
        self.cov_ll = 0.0;
        self.cov_rr = 0.0;
        self.pca_count = 0;
        self.pca_dominant_sign = 1.0;
        self.pca_sign_smooth = 1.0;
        self.smooth_gain = 1.0;
        self.gain_initialised = false;
        self.lim_env = 0.0;
        self.win_pre_boost_peak = 0.0;
        self.win_post_boost_peak = 0.0;
        self.win_out_peak = 0.0;
        self.win_max_gr_db = 0.0;
        self.sum_sq_l = 0.0;
        self.sum_sq_r = 0.0;
        self.win_frames = 0;
    }

    #[inline]
    fn apply_mastering_limiter(&mut self, in_l: f32, in_r: f32) -> (f32, f32, f32) {
        let sr = self.sample_rate as f32;
        let ceiling = 0.985_f32;
        let peak = in_l.abs().max(in_r.abs());
        let att_coef = 1.0 - (-1.0 / (0.0005 * sr)).exp();
        let rel_coef = 1.0 - (-1.0 / (0.080 * sr)).exp();
        if peak > self.lim_env {
            self.lim_env += att_coef * (peak - self.lim_env);
        } else {
            self.lim_env += rel_coef * (peak - self.lim_env);
        }
        let gain = if self.lim_env > ceiling {
            ceiling / self.lim_env
        } else {
            1.0
        };
        let gr_db = if gain < 1.0 {
            -20.0 * gain.max(1e-6).log10()
        } else {
            0.0
        };
        let out_l = (in_l * gain).clamp(-1.0, 1.0);
        let out_r = (in_r * gain).clamp(-1.0, 1.0);
        (out_l, out_r, gr_db)
    }

    /// Process a block of interleaved f32 frames.
    /// `telemetry_out` (length >= 16) receives:
    /// [0]=preBoostPeak, [1]=postBoostPeak, [2]=outPeak, [3]=maxGrDb,
    /// [4]=rmsL, [5]=rmsR,
    /// [6..9]=dynEqDetectedDb[0..3], [9..12]=dynEqGainDeltaDb[0..3],
    /// [12]=declipRepaired, [13]=declipActive.
    pub fn process_block_f32(
        &mut self,
        in_buf: &[f32],
        out_buf: &mut [f32],
        num_frames: usize,
        in_channels: usize,
        out_channels: usize,
        params: &PipelineParams,
        telemetry_out: &mut [f32],
    ) {
        if params.eq_enabled {
            self.eq.set_all_bands(&params.eq_gains);
        }

        let sr = self.sample_rate as f32;
        let rc = 1.0 / (2.0 * std::f32::consts::PI * params.crossover_hz.max(20.0));
        let dt = 1.0 / sr;
        let alpha_lp = dt / (rc + dt);
        let pca_block = (self.sample_rate as usize / 50).max(64);
        let sign_coef = 1.0 - (-1.0 / (0.025 * sr)).exp();
        let gain_coef = 1.0 - (-1.0 / (0.015 * sr)).exp();
        let cf_sec = params.cf_sec.max(0.001);

        self.win_pre_boost_peak = 0.0;
        self.win_post_boost_peak = 0.0;
        self.win_out_peak = 0.0;
        self.win_max_gr_db = 0.0;
        self.sum_sq_l = 0.0;
        self.sum_sq_r = 0.0;
        self.win_frames = num_frames;

        for frame in 0..num_frames {
            let in_off = frame * in_channels;
            let raw_l = in_buf[in_off];
            let raw_r = if in_channels > 1 { in_buf[in_off + 1] } else { raw_l };

            let (clean_l, clean_r) = if params.declip.enabled {
                self.declip.process_stereo_sample(raw_l, raw_r, &params.declip)
            } else {
                (raw_l, raw_r)
            };

            self.loud_norm.push_lookahead_sample(clean_l, clean_r);

            self.in_ring_l[self.ring_write_pos] = clean_l;
            self.in_ring_r[self.ring_write_pos] = clean_r;
            self.ring_write_pos = (self.ring_write_pos + 1) & RING_MASK;
            self.abs_count += 1;
            self.samples_since_hop += 1;

            if self.samples_since_hop >= HOP_SIZE {
                self.samples_since_hop = 0;
                if self.abs_count >= FFT_SIZE as i64 {
                    for k in 0..FFT_SIZE {
                        let idx = (self.ring_write_pos + k) & RING_MASK;
                        self.ordered_l[k] = self.in_ring_l[idx];
                        self.ordered_r[k] = self.in_ring_r[idx];
                    }
                    self.stft.process_stft_hop(
                        &self.ordered_l,
                        &self.ordered_r,
                        params.algo,
                        params.blend,
                        params.crossover_hz,
                        &mut self.win_time,
                        &mut self.win_sq,
                        &mut self.vis_spec,
                        &mut self.vis_naive,
                        &mut self.stft_metrics,
                    );
                    let base = (self.abs_count - FFT_SIZE as i64) as usize;
                    for k in 0..FFT_SIZE {
                        let p = (base + k) & OLA_MASK;
                        self.ola_out[p] += self.win_time[k];
                        self.ola_norm[p] += self.win_sq[k];
                    }
                }
            }

            let d_l = self.in_ring_l[self.ring_write_pos];
            let d_r = self.in_ring_r[self.ring_write_pos];

            let mut stft_sample = 0.5 * (d_l + d_r);
            if self.abs_count >= FFT_SIZE as i64 {
                let p = ((self.abs_count - FFT_SIZE as i64) as usize) & OLA_MASK;
                let norm = self.ola_norm[p];
                if norm > 0.1 {
                    stft_sample = self.ola_out[p] / norm;
                }
                self.ola_out[p] = 0.0;
                self.ola_norm[p] = 0.0;
            } else {
                stft_sample = 0.0;
            }

            // Dynamic PCA on delayed signal
            self.lp_l += alpha_lp * (d_l - self.lp_l);
            self.lp_r += alpha_lp * (d_r - self.lp_r);
            let hf_l = d_l - self.lp_l;
            let hf_r = d_r - self.lp_r;
            self.cov_ll += hf_l * hf_l;
            self.cov_rr += hf_r * hf_r;
            self.pca_count += 1;
            if self.pca_count >= pca_block {
                if self.cov_ll > self.cov_rr * 1.25 {
                    self.pca_dominant_sign = 1.0;
                } else if self.cov_rr > self.cov_ll * 1.25 {
                    self.pca_dominant_sign = -1.0;
                }
                self.cov_ll = 0.0;
                self.cov_rr = 0.0;
                self.pca_count = 0;
            }
            self.pca_sign_smooth += sign_coef * (self.pca_dominant_sign - self.pca_sign_smooth);

            let out_sample_count = (self.abs_count - (FFT_SIZE as i64 - 1)).max(0);
            let out_elapsed_sec = out_sample_count as f32 / sr;
            let head_fade = if params.is_crossfade_active {
                (out_elapsed_sec / cf_sec).clamp(0.0, 1.0)
            } else {
                1.0
            };
            let target_gain = params.preamp_linear * params.base_external_multiplier * head_fade;

            if !self.gain_initialised {
                self.smooth_gain = if params.is_crossfade_active { 0.0 } else { target_gain };
                self.gain_initialised = true;
            }
            self.smooth_gain += gain_coef * (target_gain - self.smooth_gain);

            let (pre_peak, mut out_l, mut out_r) = if params.algo == ALGO_STEREO_REF {
                let pre = d_l.abs().max(d_r.abs());
                let (mut l, mut r) = self.loud_norm.process_stereo_sample(d_l, d_r, &params.loudness);
                if params.dyn_eq.enabled {
                    (l, r) = self.dyn_eq.process_stereo_sample(l, r, &params.dyn_eq);
                }
                if params.eq_enabled {
                    (l, r) = self.eq.process_stereo_sample(l, r);
                }
                (pre, l * self.smooth_gain, r * self.smooth_gain)
            } else {
                let mono_core = match params.algo {
                    ALGO_NAIVE => 0.5 * (d_l + d_r),
                    ALGO_DYNAMIC_PCA => {
                        let mid_hf = 0.5 * (hf_l + hf_r);
                        let side_hf = 0.5 * (hf_l - hf_r);
                        let lf_mid = 0.5 * (self.lp_l + self.lp_r);
                        let beta = params.blend.min(1.3);
                        lf_mid + mid_hf + beta * self.pca_sign_smooth * side_hf
                    }
                    ALGO_HILBERT | ALGO_STFT_ENERGY | ALGO_PHASE_ALIGN => stft_sample,
                    _ => 0.5 * (d_l + d_r),
                };

                let (sp_l, sp_r) = self.stereo_depth.process_sample(mono_core, d_l, d_r, &params.spatial);
                let pre = sp_l.abs().max(sp_r.abs());
                if (sp_l - sp_r).abs() < 1e-12 {
                    let mut m = self.loud_norm.process_mono_sample(sp_l, &params.loudness);
                    if params.dyn_eq.enabled {
                        m = self.dyn_eq.process_mono_sample(m, &params.dyn_eq);
                    }
                    if params.eq_enabled {
                        m = self.eq.process_mono_sample(m);
                    }
                    m *= self.smooth_gain;
                    (pre, m, m)
                } else {
                    let (mut l, mut r) = self.loud_norm.process_stereo_sample(sp_l, sp_r, &params.loudness);
                    if params.dyn_eq.enabled {
                        (l, r) = self.dyn_eq.process_stereo_sample(l, r, &params.dyn_eq);
                    }
                    if params.eq_enabled {
                        (l, r) = self.eq.process_stereo_sample(l, r);
                    }
                    (pre, l * self.smooth_gain, r * self.smooth_gain)
                }
            };

            if pre_peak > self.win_pre_boost_peak {
                self.win_pre_boost_peak = pre_peak;
            }
            let post_boost = out_l.abs().max(out_r.abs());
            if post_boost > self.win_post_boost_peak {
                self.win_post_boost_peak = post_boost;
            }

            let (lim_l, lim_r, gr_db) = self.apply_mastering_limiter(out_l, out_r);
            out_l = lim_l;
            out_r = lim_r;

            let lim_peak = out_l.abs().max(out_r.abs());
            if lim_peak > self.win_out_peak {
                self.win_out_peak = lim_peak;
            }
            if gr_db > self.win_max_gr_db {
                self.win_max_gr_db = gr_db;
            }
            self.sum_sq_l += (out_l as f64) * (out_l as f64);
            self.sum_sq_r += (out_r as f64) * (out_r as f64);

            let out_off = frame * out_channels;
            out_buf[out_off] = out_l;
            if out_channels > 1 {
                out_buf[out_off + 1] = out_r;
                for ch in 2..out_channels {
                    out_buf[out_off + ch] = 0.0;
                }
            }
        }

        if telemetry_out.len() >= 14 {
            let n = (self.win_frames.max(1)) as f64;
            telemetry_out[0] = self.win_pre_boost_peak;
            telemetry_out[1] = self.win_post_boost_peak;
            telemetry_out[2] = self.win_out_peak;
            telemetry_out[3] = self.win_max_gr_db;
            telemetry_out[4] = (self.sum_sq_l / n).sqrt() as f32;
            telemetry_out[5] = (self.sum_sq_r / n).sqrt() as f32;
            let det = self.dyn_eq.detected_bands_db();
            let dlt = self.dyn_eq.dynamic_gain_deltas_db();
            telemetry_out[6] = det[0];
            telemetry_out[7] = det[1];
            telemetry_out[8] = det[2];
            telemetry_out[9] = dlt[0];
            telemetry_out[10] = dlt[1];
            telemetry_out[11] = dlt[2];
            let dc_tel = self.declip.get_telemetry();
            telemetry_out[12] = dc_tel.repaired_samples_total as f32;
            telemetry_out[13] = dc_tel.current_clip_rate;
            if telemetry_out.len() >= 78 {
                telemetry_out[14..78].copy_from_slice(&self.vis_spec);
            }
        }
    }
}
