//! Native STFT Mono Processing, Phase-Alignment & 32-Band Visualizer Engine.
//!
//! Replaces Kotlin's per-hop STFT loop, frequency-domain matrix mathematics,
//! Hann synthesis, OLA reconstruction, and Dynamic PCA state machines with zero-allocation,
//! vectorised native Rust code.

use crate::fft::FastFftEngine;
use crate::math_guard::sanitize_f32;
use std::f32::consts::PI;

pub const FFT_SIZE: usize = 2048;
pub const HOP_SIZE: usize = 512;
pub const NUM_BANDS: usize = 32;
const BAND_MIN_HZ: f32 = 40.0;
const BAND_MAX_HZ: f32 = 18000.0;

/// Mono algorithm codes corresponding to `MonoAlgorithm` in Kotlin.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StftAlgoCode {
    Naive = 0,
    Hilbert = 1,
    StftEnergy = 2,
    PhaseAlign = 3,
    DynamicPca = 4,
    StereoRef = 5,
}

pub struct StftMonoEngine {
    fft: FastFftEngine,
    window_func: Vec<f32>,
    l_r: Vec<f32>,
    l_i: Vec<f32>,
    r_r: Vec<f32>,
    r_i: Vec<f32>,
    out_r: Vec<f32>,
    out_i: Vec<f32>,
    act_mag: Vec<f32>,
    naive_mag: Vec<f32>,

    band_lo: [usize; NUM_BANDS],
    band_hi: [usize; NUM_BANDS],
    bands_sample_rate: u32,

    vis_spectrum: [f32; NUM_BANDS],
    vis_naive: [f32; NUM_BANDS],
    metric_corr: f32,
    metric_loss_db: f32,

    // Dynamic PCA State
    lp_l: f32,
    lp_r: f32,
    cov_ll: f32,
    cov_rr: f32,
    pca_count: usize,
    pca_dominant_sign: f32,
    pca_sign_smooth: f32,
}

impl StftMonoEngine {
    pub fn new(sample_rate: u32) -> Self {
        let sr = sample_rate.max(8000);
        let mut window = vec![0.0_f32; FFT_SIZE];
        for i in 0..FFT_SIZE {
            window[i] = 0.5 * (1.0 - (2.0 * PI * (i as f32) / (FFT_SIZE as f32)).cos());
        }

        let mut engine = Self {
            fft: FastFftEngine::new(FFT_SIZE),
            window_func: window,
            l_r: vec![0.0; FFT_SIZE],
            l_i: vec![0.0; FFT_SIZE],
            r_r: vec![0.0; FFT_SIZE],
            r_i: vec![0.0; FFT_SIZE],
            out_r: vec![0.0; FFT_SIZE],
            out_i: vec![0.0; FFT_SIZE],
            act_mag: vec![0.0; FFT_SIZE / 2],
            naive_mag: vec![0.0; FFT_SIZE / 2],
            band_lo: [1; NUM_BANDS],
            band_hi: [1; NUM_BANDS],
            bands_sample_rate: 0,
            vis_spectrum: [0.0; NUM_BANDS],
            vis_naive: [0.0; NUM_BANDS],
            metric_corr: 0.85,
            metric_loss_db: -2.4,

            lp_l: 0.0,
            lp_r: 0.0,
            cov_ll: 0.0,
            cov_rr: 0.0,
            pca_count: 0,
            pca_dominant_sign: 1.0,
            pca_sign_smooth: 1.0,
        };

        engine.ensure_bands(sr);
        engine
    }

    pub fn set_sample_rate(&mut self, sample_rate: u32) {
        let sr = sample_rate.max(8000);
        self.ensure_bands(sr);
    }

    pub fn reset(&mut self) {
        self.l_r.fill(0.0);
        self.l_i.fill(0.0);
        self.r_r.fill(0.0);
        self.r_i.fill(0.0);
        self.out_r.fill(0.0);
        self.out_i.fill(0.0);
        self.act_mag.fill(0.0);
        self.naive_mag.fill(0.0);
        self.vis_spectrum.fill(0.0);
        self.vis_naive.fill(0.0);
        self.metric_corr = 0.85;
        self.metric_loss_db = -2.4;

        self.lp_l = 0.0;
        self.lp_r = 0.0;
        self.cov_ll = 0.0;
        self.cov_rr = 0.0;
        self.pca_count = 0;
        self.pca_dominant_sign = 1.0;
        self.pca_sign_smooth = 1.0;
    }

    fn ensure_bands(&mut self, sr: u32) {
        if self.bands_sample_rate == sr {
            return;
        }
        self.bands_sample_rate = sr;
        let step = (sr as f32) / (FFT_SIZE as f32);
        let max_bin = FFT_SIZE / 2 - 1;
        let ratio = BAND_MAX_HZ / BAND_MIN_HZ;

        for b in 0..NUM_BANDS {
            let f_lo = BAND_MIN_HZ * ratio.powf((b as f32) / (NUM_BANDS as f32));
            let f_hi = BAND_MIN_HZ * ratio.powf(((b + 1) as f32) / (NUM_BANDS as f32));
            let mut k_lo = (f_lo / step).ceil() as usize;
            let mut k_hi = (f_hi / step).floor() as usize;
            if k_hi < k_lo {
                let k = ((f_lo * f_hi).sqrt() / step).round() as usize;
                k_lo = k;
                k_hi = k;
            }
            self.band_lo[b] = k_lo.clamp(1, max_bin);
            self.band_hi[b] = k_hi.clamp(self.band_lo[b], max_bin);
        }
    }

    /// Executes a single STFT Hop:
    /// 1. Windowing and Forward FFT on Left and Right channels.
    /// 2. Frequency-domain mono reconstruction according to `algo_code`.
    /// 3. High-frequency phase correlation & power loss tracking.
    /// 4. 32-band RMS spectrum extraction with logarithmic tilt & attack/release smoothing.
    /// 5. Inverse FFT and Hann window synthesis.
    ///
    /// The computed synthesized samples are written to `out_windowed_time` (length 2048)
    /// and `out_window_sq` (length 2048) for caller Overlap-Add accumulation.
    pub fn process_stft_hop(
        &mut self,
        ring_l: &[f32], // 2048 samples ordered from oldest to newest
        ring_r: &[f32], // 2048 samples
        algo_code: i32,
        blend: f32,
        crossover_freq: f32,
        out_windowed_time: &mut [f32],
        out_window_sq: &mut [f32],
        vis_spec_out: &mut [f32],
        vis_naive_out: &mut [f32],
        metrics_out: &mut [f32],
    ) {
        let sr = self.bands_sample_rate.max(8000) as f32;
        let blend = sanitize_f32(blend).clamp(0.0, 1.5);
        let crossover_freq = sanitize_f32(crossover_freq).max(100.0);

        for i in 0..FFT_SIZE {
            let w = self.window_func[i];
            self.l_r[i] = sanitize_f32(ring_l[i]) * w;
            self.l_i[i] = 0.0;
            self.r_r[i] = sanitize_f32(ring_r[i]) * w;
            self.r_i[i] = 0.0;
        }

        self.fft.transform(&mut self.l_r, &mut self.l_i, false);
        self.fft.transform(&mut self.r_r, &mut self.r_i, false);

        let half = FFT_SIZE / 2;
        // DC & Nyquist: plain mid average
        self.out_r[0] = 0.5 * (self.l_r[0] + self.r_r[0]);
        self.out_i[0] = 0.0;
        self.out_r[half] = 0.5 * (self.l_r[half] + self.r_r[half]);
        self.out_i[half] = 0.0;

        let bin_freq_step = sr / (FFT_SIZE as f32);
        let mut high_mid_power = 1e-9_f32;
        let mut high_stereo_power = 1e-9_f32;
        let mut corr_numer = 0.0_f32;
        let mut corr_denom_l = 1e-9_f32;
        let mut corr_denom_r = 1e-9_f32;

        let algo = match algo_code {
            0 => StftAlgoCode::Naive,
            1 => StftAlgoCode::Hilbert,
            2 => StftAlgoCode::StftEnergy,
            3 => StftAlgoCode::PhaseAlign,
            4 => StftAlgoCode::DynamicPca,
            _ => StftAlgoCode::StereoRef,
        };

        for k in 1..half {
            let freq = (k as f32) * bin_freq_step;
            let hf_weight = if freq < crossover_freq * 0.7 {
                0.0
            } else if freq > crossover_freq * 1.3 {
                1.0
            } else {
                (freq - crossover_freq * 0.7) / (crossover_freq * 0.6)
            };

            let lr = self.l_r[k];
            let li = self.l_i[k];
            let rr = self.r_r[k];
            let ri = self.r_i[k];

            let mid_r = 0.5 * (lr + rr);
            let mid_i = 0.5 * (li + ri);
            let side_r = 0.5 * (lr - rr);
            let side_i = 0.5 * (li - ri);

            let mag_l2 = lr * lr + li * li;
            let mag_r2 = rr * rr + ri * ri;
            let mag_mid = (mid_r * mid_r + mid_i * mid_i).sqrt();

            if freq >= 3500.0 {
                high_mid_power += mag_mid * mag_mid;
                high_stereo_power += 0.5 * (mag_l2 + mag_r2);
                corr_numer += lr * rr + li * ri;
                corr_denom_l += mag_l2;
                corr_denom_r += mag_r2;
            }

            let (o_r, o_i) = match algo {
                StftAlgoCode::StftEnergy => {
                    let target = (0.5 * (mag_l2 + mag_r2)).sqrt();
                    let final_mag = mag_mid + (target - mag_mid) * (blend * hf_weight).min(1.0);
                    if mag_mid > 1e-7 {
                        (final_mag * mid_r / mag_mid, final_mag * mid_i / mag_mid)
                    } else if mag_l2 >= mag_r2 && mag_l2 > 1e-12 {
                        let m_l = mag_l2.sqrt();
                        (final_mag * lr / m_l, final_mag * li / m_l)
                    } else if mag_r2 > 1e-12 {
                        let m_r = mag_r2.sqrt();
                        (final_mag * rr / m_r, final_mag * ri / m_r)
                    } else {
                        (0.0, 0.0)
                    }
                }
                StftAlgoCode::PhaseAlign => {
                    let mag_l = mag_l2.sqrt();
                    let mag_r = mag_r2.sqrt();
                    let mut a_rr = rr;
                    let mut a_ri = ri;
                    if mag_l > 1e-7 && mag_r > 1e-7 {
                        let factor = (blend * hf_weight).min(1.0);
                        a_rr = rr + (mag_r * (lr / mag_l) - rr) * factor;
                        a_ri = ri + (mag_r * (li / mag_l) - ri) * factor;
                    }
                    (0.5 * (lr + a_rr), 0.5 * (li + a_ri))
                }
                _ => {
                    // Hilbert, Naive, DynamicPca, StereoRef analysis path:
                    // Mono = Mid + g * (-j) * Side
                    let g = blend * hf_weight;
                    (mid_r + g * side_i, mid_i - g * side_r)
                }
            };

            self.out_r[k] = o_r;
            self.out_i[k] = o_i;
            let sym_k = FFT_SIZE - k;
            self.out_r[sym_k] = o_r;
            self.out_i[sym_k] = -o_i;

            self.act_mag[k] = match algo {
                StftAlgoCode::Naive => mag_mid,
                StftAlgoCode::StereoRef => (0.5 * (mag_l2 + mag_r2)).sqrt(),
                StftAlgoCode::DynamicPca => mag_mid.max((0.5 * (mag_l2 + mag_r2)).sqrt()),
                _ => (o_r * o_r + o_i * o_i).sqrt(),
            };
            self.naive_mag[k] = mag_mid;
        }

        // Visualizer bands: RMS over bins of each of the 32 bands
        let amp_norm = 1.0 / ((FFT_SIZE as f32) * 0.25);
        for b in 0..NUM_BANDS {
            let mut sa = 0.0_f32;
            let mut sn = 0.0_f32;
            let lo = self.band_lo[b];
            let hi = self.band_hi[b];
            for k in lo..=hi {
                sa += self.act_mag[k] * self.act_mag[k];
                sn += self.naive_mag[k] * self.naive_mag[k];
            }
            let n = (hi - lo + 1) as f32;
            let amp_a = (sa / n).sqrt() * amp_norm;
            let amp_n = (sn / n).sqrt() * amp_norm;
            let tilt = (b as f32) * 0.375;
            let lv_a = ((20.0 * amp_a.max(1e-5).log10() + 80.0 + tilt) / 80.0).clamp(0.0, 1.0);
            let lv_n = ((20.0 * amp_n.max(1e-5).log10() + 80.0 + tilt) / 80.0).clamp(0.0, 1.0);

            // Fast attack, smooth release
            self.vis_spectrum[b] = if lv_a > self.vis_spectrum[b] {
                lv_a
            } else {
                self.vis_spectrum[b] * 0.82 + lv_a * 0.18
            };
            self.vis_naive[b] = if lv_n > self.vis_naive[b] {
                lv_n
            } else {
                self.vis_naive[b] * 0.82 + lv_n * 0.18
            };
        }

        let denom = (corr_denom_l * corr_denom_r).sqrt();
        let instant_corr = if denom > 1e-12 {
            (corr_numer / denom).clamp(-1.0, 1.0)
        } else {
            1.0
        };
        let instant_loss =
            (10.0 * (high_mid_power / high_stereo_power.max(0.004)).log10()).clamp(-24.0, 0.0);

        self.metric_corr = self.metric_corr * 0.85 + instant_corr * 0.15;
        self.metric_loss_db = self.metric_loss_db * 0.85 + instant_loss * 0.15;

        // Inverse FFT back to time domain
        self.fft.transform(&mut self.out_r, &mut self.out_i, true);

        // Synthesis windowing
        for i in 0..FFT_SIZE {
            let w = self.window_func[i];
            out_windowed_time[i] = self.out_r[i] * w;
            out_window_sq[i] = w * w;
        }

        // Copy telemetry to caller slices
        if vis_spec_out.len() >= NUM_BANDS {
            vis_spec_out[..NUM_BANDS].copy_from_slice(&self.vis_spectrum);
        }
        if vis_naive_out.len() >= NUM_BANDS {
            vis_naive_out[..NUM_BANDS].copy_from_slice(&self.vis_naive);
        }
        if metrics_out.len() >= 2 {
            metrics_out[0] = self.metric_corr;
            metrics_out[1] = self.metric_loss_db;
        }
    }

    /// Native Dynamic PCA block processing:
    /// Computes RC low-pass split, covariance matrix estimation, hysteresis sign lock,
    /// and dynamic principal component injection for an array of delayed stereo samples.
    pub fn process_dynamic_pca_block(
        &mut self,
        dl_block: &[f32],
        dr_block: &[f32],
        out_mono: &mut [f32],
        blend: f32,
        crossover_hz: f32,
        sample_rate: u32,
    ) {
        let sr = sample_rate.max(8000) as f32;
        let rc = 1.0 / (2.0 * PI * crossover_hz.max(100.0));
        let alpha_lp = (1.0 / sr) / (rc + 1.0 / sr);
        let sign_coef = 1.0 - (-1.0 / (sr * 0.006)).exp();
        let beta = blend.clamp(0.0, 1.3);

        let n = dl_block.len().min(dr_block.len()).min(out_mono.len());
        for i in 0..n {
            let dl = sanitize_f32(dl_block[i]);
            let dr = sanitize_f32(dr_block[i]);

            self.lp_l += alpha_lp * (dl - self.lp_l);
            self.lp_r += alpha_lp * (dr - self.lp_r);
            let hf_l = dl - self.lp_l;
            let hf_r = dr - self.lp_r;
            self.cov_ll += hf_l * hf_l;
            self.cov_rr += hf_r * hf_r;
            self.pca_count += 1;

            if self.pca_count >= 256 {
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

            let mid_hf = 0.5 * (hf_l + hf_r);
            let side_hf = 0.5 * (hf_l - hf_r);
            let lf_mid = 0.5 * (self.lp_l + self.lp_r);

            out_mono[i] = sanitize_f32(lf_mid + mid_hf + beta * self.pca_sign_smooth * side_hf);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_stft_mono_hop_reconstruction() {
        let mut engine = StftMonoEngine::new(48000);
        let mut ring_l = vec![0.0_f32; FFT_SIZE];
        let mut ring_r = vec![0.0_f32; FFT_SIZE];

        // 440Hz test sine tone + 5000Hz high frequency tone (to activate HF phase correlation metric)
        for i in 0..FFT_SIZE {
            let phase_low = 2.0 * PI * 440.0 * (i as f32) / 48000.0;
            let phase_high = 2.0 * PI * 5000.0 * (i as f32) / 48000.0;
            ring_l[i] = phase_low.sin() * 0.5 + phase_high.sin() * 0.4;
            ring_r[i] = phase_low.sin() * 0.4 + phase_high.sin() * 0.35;
        }

        let mut out_time = vec![0.0_f32; FFT_SIZE];
        let mut out_norm = vec![0.0_f32; FFT_SIZE];
        let mut vis_spec = [0.0_f32; NUM_BANDS];
        let mut vis_naive = [0.0_f32; NUM_BANDS];
        let mut metrics = [0.0_f32; 2];

        // Run multiple hops to allow telemetry smoothing filter to converge
        for _ in 0..4 {
            engine.process_stft_hop(
                &ring_l,
                &ring_r,
                StftAlgoCode::StftEnergy as i32,
                1.0,
                2400.0,
                &mut out_time,
                &mut out_norm,
                &mut vis_spec,
                &mut vis_naive,
                &mut metrics,
            );
        }

        // Verify window energy and non-zero audio output
        let mut sum_out = 0.0;
        for i in 0..FFT_SIZE {
            sum_out += out_time[i].abs();
        }
        assert!(sum_out > 10.0);
        // Phase correlation of in-phase sine should be near 1.0
        assert!(metrics[0] > 0.90);
    }

    #[test]
    fn test_dynamic_pca_block_stability() {
        let mut engine = StftMonoEngine::new(48000);
        let block_size = 512;
        let mut dl = vec![0.0_f32; block_size];
        let mut dr = vec![0.0_f32; block_size];
        let mut out_mono = vec![0.0_f32; block_size];

        for i in 0..block_size {
            dl[i] = ((i as f32) * 0.2).sin();
            dr[i] = ((i as f32) * 0.2).cos() * 0.3;
        }

        engine.process_dynamic_pca_block(&dl, &dr, &mut out_mono, 1.0, 1800.0, 48000);

        for &val in &out_mono {
            assert!(val.is_finite());
            assert!(val.abs() <= 1.5);
        }
    }
}
