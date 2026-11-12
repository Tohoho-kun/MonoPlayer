//! Anti-Clipping Reconstruction & De-Clicking Engine in Rust for MonoPlayer.
//!
//! Restores clipped waveforms (both 0 dBFS digital hard clipping and scaled/attenuated
//! master clipping where tracks were lowered in volume post-mastering, causing crackling/clicks).
//!
//! Uses zero-allocation ring buffering, adaptive plateau detection, cusp/discontinuity
//! smoothing, and cubic Hermite spline peak reconstruction with numerical safety guards.

use crate::math_guard::sanitize_f32;

pub const DECLIP_LOOKAHEAD_SAMPLES: usize = 48;
pub const DECLIP_RING_SIZE: usize = 128;
const DECLIP_RING_MASK: usize = DECLIP_RING_SIZE - 1;
const MAX_CLIP_BURST: usize = 24;

/// Configuration parameters for the declipping engine.
#[derive(Clone, Copy, Debug)]
pub struct DeclipParams {
    /// Whether declipping and declicking is active.
    pub enabled: bool,
    /// Detection sensitivity (0.0 .. 1.0, default ~0.70).
    /// Higher values detect finer plateaus and softer pre-scaled mastering clips.
    pub sensitivity: f32,
    /// Minimum absolute amplitude threshold (linear 0.0 .. 1.0, default 0.12 ~ -18 dBFS).
    /// Prevents false detection on quiet passages or dithered silence.
    pub min_amplitude: f32,
    /// Maximum allowed peak reconstruction multiplier relative to the plateau level (1.1 .. 2.0, default 1.50).
    pub max_reconstruct_ratio: f32,
}

impl Default for DeclipParams {
    fn default() -> Self {
        Self {
            enabled: false,
            sensitivity: 0.70,
            min_amplitude: 0.12,
            max_reconstruct_ratio: 1.50,
        }
    }
}

/// Real-time metrics and diagnostic telemetry for the declipper.
#[derive(Clone, Copy, Debug)]
pub struct DeclipTelemetry {
    /// Number of clipping/click events repaired.
    pub repaired_events_total: u64,
    /// Total samples that have been reconstructed.
    pub repaired_samples_total: u64,
    /// Running clip activity ratio (0.0 .. 1.0).
    pub current_clip_rate: f32,
    /// Estimated clipping ceiling in dBFS (e.g. 0.0 dB for full-scale, -3.0 dB for lowered master).
    pub estimated_threshold_db: f32,
    /// Highest reconstructed peak amplitude (linear, can exceed 1.0 before downstream soft limiting).
    pub max_reconstructed_peak: f32,
}

impl Default for DeclipTelemetry {
    fn default() -> Self {
        Self {
            repaired_events_total: 0,
            repaired_samples_total: 0,
            current_clip_rate: 0.0,
            estimated_threshold_db: 0.0,
            max_reconstructed_peak: 0.0,
        }
    }
}

/// Single-channel zero-allocation declipping & de-clicking processor.
pub struct DeclipChannel {
    ring: [f32; DECLIP_RING_SIZE],
    repaired_flag: [bool; DECLIP_RING_SIZE],
    write_idx: usize,
    sample_rate: i32,
    events_count: u64,
    samples_count: u64,
    recent_clip_samples: u32,
    recent_total_samples: u32,
    estimated_level: f32,
    max_peak: f32,
}

impl DeclipChannel {
    pub fn new(sample_rate: i32) -> Self {
        Self {
            ring: [0.0; DECLIP_RING_SIZE],
            repaired_flag: [false; DECLIP_RING_SIZE],
            write_idx: 0,
            sample_rate: sample_rate.max(8000),
            events_count: 0,
            samples_count: 0,
            recent_clip_samples: 0,
            recent_total_samples: 0,
            estimated_level: 1.0,
            max_peak: 0.0,
        }
    }

    pub fn reset(&mut self) {
        self.ring.fill(0.0);
        self.repaired_flag.fill(false);
        self.write_idx = 0;
        self.events_count = 0;
        self.samples_count = 0;
        self.recent_clip_samples = 0;
        self.recent_total_samples = 0;
        self.estimated_level = 1.0;
        self.max_peak = 0.0;
    }

    pub fn set_sample_rate(&mut self, sr: i32) {
        self.sample_rate = sr.max(8000);
        self.reset();
    }

    #[inline(always)]
    fn get_sample(&self, offset_from_write: isize) -> f32 {
        let idx = ((self.write_idx as isize + offset_from_write)
            .rem_euclid(DECLIP_RING_SIZE as isize)) as usize;
        self.ring[idx]
    }

    #[inline(always)]
    fn set_sample(&mut self, offset_from_write: isize, value: f32) {
        let idx = ((self.write_idx as isize + offset_from_write)
            .rem_euclid(DECLIP_RING_SIZE as isize)) as usize;
        self.ring[idx] = sanitize_f32(value);
        self.repaired_flag[idx] = true;
    }

    #[inline(always)]
    fn is_repaired(&self, offset_from_write: isize) -> bool {
        let idx = ((self.write_idx as isize + offset_from_write)
            .rem_euclid(DECLIP_RING_SIZE as isize)) as usize;
        self.repaired_flag[idx]
    }

    /// Process one incoming sample, returning the declipped sample delayed by
    /// `DECLIP_LOOKAHEAD_SAMPLES` (48 samples, ~1.0 ms at 48kHz).
    pub fn process_sample(&mut self, input: f32, params: &DeclipParams) -> f32 {
        let clean_in = sanitize_f32(input);
        self.ring[self.write_idx] = clean_in;
        self.repaired_flag[self.write_idx] = false;

        self.recent_total_samples += 1;
        if self.recent_total_samples >= 4096 {
            self.recent_clip_samples = (self.recent_clip_samples as f32 * 0.5) as u32;
            self.recent_total_samples = 2048;
        }

        if params.enabled {
            self.detect_and_repair(params);
        }

        // Retrieve the output sample delayed by DECLIP_LOOKAHEAD_SAMPLES
        let out_idx = (self.write_idx + DECLIP_RING_SIZE - DECLIP_LOOKAHEAD_SAMPLES) & DECLIP_RING_MASK;
        let out_val = self.ring[out_idx];

        self.write_idx = (self.write_idx + 1) & DECLIP_RING_MASK;
        out_val
    }

    /// Inspect the lookahead window to detect and repair flat plateaus and cusp discontinuities.
    fn detect_and_repair(&mut self, params: &DeclipParams) {
        // We inspect around anchor `p = -4` (4 samples behind latest write),
        // guaranteeing that post-clip samples (+1, +2, +3, +4) exist in the ring.
        let p: isize = -4;

        if self.is_repaired(p) {
            return;
        }

        let ref_val = self.get_sample(p);
        let abs_ref = ref_val.abs();
        if abs_ref < params.min_amplitude {
            return;
        }

        // Plateau tolerance based on sensitivity:
        // sensitivity 0.0 -> tolerance ~1.6 LSB (16-bit exact / requantized plateaus)
        // sensitivity 1.0 -> tolerance 0.00200 (detects dithered/scaled master clipping)
        let s_clamped = params.sensitivity.clamp(0.0, 1.0);
        let tolerance = 0.00005 + s_clamped * 0.00195;

        // 1. Scan backwards to find the start of the plateau
        let mut plateau_len: isize = 1;
        let mut k: isize = p - 1;
        let max_scan = p - (MAX_CLIP_BURST as isize);

        while k >= max_scan {
            if self.is_repaired(k) {
                break;
            }
            let s = self.get_sample(k);
            if (s.abs() - abs_ref).abs() <= tolerance && s.signum() == ref_val.signum() {
                plateau_len += 1;
                k -= 1;
            } else {
                break;
            }
        }

        let start_pos = p - plateau_len + 1; // start index relative to write_idx
        let end_pos = p;                    // end index relative to write_idx

        let is_positive = ref_val > 0.0;
        let s_pre = self.get_sample(start_pos - 1);
        let s_post = self.get_sample(end_pos + 1);

        let mut is_clipped_crest = false;

        // Minimum flat-top length: low sensitivity demands longer, unambiguous plateaus.
        // 0.2 -> 5 samples, 0.5 -> 4 samples, ~1.0 -> 3 samples. (2-sample "plateaus" occur
        // naturally at every smooth crest, so they are never treated as clipping.)
        let min_len = (5.0 - 2.0 * s_clamped).round().max(3.0) as isize;

        if plateau_len >= min_len {
            // Only evaluate once the plateau has ended (the next sample leaves the flat top),
            // so the entire flat burst is repaired at once.
            let post_leaves = (s_post.abs() - abs_ref).abs() > tolerance
                || s_post.signum() != ref_val.signum();
            if post_leaves {
                // Corner sharpness: a real clip slices the waveform, so the samples immediately
                // outside the plateau sit clearly below it. A natural smooth crest (bass, sustained
                // tones) only drifts away by ~tolerance and is rejected here.
                let s_pre2 = self.get_sample(start_pos - 2);
                let s_post2 = self.get_sample(end_pos + 2);
                let (d_in, d_out, slope_in, slope_out) = if is_positive {
                    (ref_val - s_pre, ref_val - s_post, s_pre - s_pre2, s_post - s_post2)
                } else {
                    (s_pre - ref_val, s_post - ref_val, s_pre2 - s_pre, s_post2 - s_post)
                };
                // The sample just outside can land very close to the clip level by chance, so the
                // approach slope (how steeply the wave runs into the ceiling) also counts.
                let edge_in = d_in.max(0.5 * slope_in);
                let edge_out = d_out.max(0.5 * slope_out);
                let corner_thresh =
                    (abs_ref * (0.030 - 0.025 * s_clamped)).max(tolerance * 6.0);
                if d_in > 0.0 && d_out > 0.0 && edge_in > corner_thresh && edge_out > corner_thresh {
                    is_clipped_crest = true;
                }
            }
        } else if plateau_len == 1
            && s_clamped > 0.60
            // A lone point only: if the next sample continues flat, this is the onset of a
            // clipped plateau, which the plateau path handles once it ends.
            && ((s_post.abs() - abs_ref).abs() > tolerance || s_post.signum() != ref_val.signum())
        {
            // Isolated click / impulse: the curvature at p must be the local extreme AND
            // dramatically larger than the surrounding curvature (pure tones / cymbals have
            // uniform curvature and never qualify).
            let d2_at = |this: &Self, o: isize| -> f32 {
                this.get_sample(o + 1) - 2.0 * this.get_sample(o) + this.get_sample(o - 1)
            };
            let d2 = d2_at(self, p);
            let d2_l = d2_at(self, p - 1);
            let d2_r = d2_at(self, p + 1);
            if d2.abs() >= d2_l.abs() && d2.abs() >= d2_r.abs() {
                let mut nb = 0.0_f32;
                for o in [-5_isize, -4, -3, -2, 2, 3] {
                    nb += d2_at(self, p + o).abs();
                }
                let nb_avg = (nb / 6.0).max(1.0e-5);
                let ratio_thresh = 10.0 - 5.0 * s_clamped; // 0.6 -> 7x, 1.0 -> 5x
                let pointing_out = (is_positive && d2 < 0.0) || (!is_positive && d2 > 0.0);
                if pointing_out && d2.abs() > ratio_thresh * nb_avg && d2.abs() > 0.06 * abs_ref {
                    is_clipped_crest = true;
                }
            }
        }

        if !is_clipped_crest {
            return;
        }

        // --- Perform Cubic Hermite Spline Peak Reconstruction ---
        self.reconstruct_peak(start_pos, end_pos, params);

        // Update telemetry tracking
        if self.events_count == 0 {
            self.estimated_level = abs_ref;
        } else {
            self.estimated_level = 0.80 * self.estimated_level + 0.20 * abs_ref;
        }
        self.events_count += 1;
        self.samples_count += plateau_len as u64;
        self.recent_clip_samples += plateau_len as u32;
    }

    /// Reconstructs the clipped interval `[start_pos, end_pos]` using Cubic Hermite Spline.
    fn reconstruct_peak(&mut self, start_pos: isize, end_pos: isize, params: &DeclipParams) {
        let burst_len = (end_pos - start_pos + 1) as usize;
        let delta_t = (burst_len + 1) as f32;

        let y0 = self.get_sample(start_pos - 1);
        let ym1 = self.get_sample(start_pos - 2);
        let ym2 = self.get_sample(start_pos - 3);

        let y1 = self.get_sample(end_pos + 1);
        let yp1 = self.get_sample(end_pos + 2);
        let yp2 = self.get_sample(end_pos + 3);

        // Estimate entering slope m0 (backward 3-point difference with fallback)
        let mut m0 = (3.0 * y0 - 4.0 * ym1 + ym2) * 0.5;
        let simple_m0 = y0 - ym1;
        if m0.signum() != simple_m0.signum() {
            m0 = simple_m0;
        }

        // Estimate exiting slope m1 (forward 3-point difference with fallback)
        let mut m1 = (-3.0 * y1 + 4.0 * yp1 - yp2) * 0.5;
        let simple_m1 = yp1 - y1;
        if m1.signum() != simple_m1.signum() {
            m1 = simple_m1;
        }

        let ref_val = self.get_sample(start_pos);
        let is_positive = ref_val >= 0.0;
        let max_allowed = ref_val.abs() * params.max_reconstruct_ratio.clamp(1.05, 2.5);

        for step in 0..burst_len {
            let offset = start_pos + step as isize;
            let t = (step as f32 + 1.0) / delta_t;
            let t2 = t * t;
            let t3 = t2 * t;

            // Hermite basis functions
            let h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
            let h10 = t3 - 2.0 * t2 + t;
            let h01 = -2.0 * t3 + 3.0 * t2;
            let h11 = t3 - t2;

            let mut recon = h00 * y0 + h10 * (m0 * delta_t) + h01 * y1 + h11 * (m1 * delta_t);

            // Shape consistency & peak ceiling soft bounding
            if is_positive {
                if burst_len > 1 {
                    let floor_bound = y0.min(y1).min(ref_val);
                    recon = recon.max(floor_bound);
                } else {
                    let floor_bound = y0.min(y1) - (y1 - y0).abs();
                    recon = recon.max(floor_bound);
                }
                if recon > max_allowed {
                    let over = recon - max_allowed;
                    recon = max_allowed + over * 0.15;
                }
            } else {
                if burst_len > 1 {
                    let ceil_bound = y0.max(y1).max(ref_val);
                    recon = recon.min(ceil_bound);
                } else {
                    let ceil_bound = y0.max(y1) + (y1 - y0).abs();
                    recon = recon.min(ceil_bound);
                }
                if recon < -max_allowed {
                    let under = recon - (-max_allowed);
                    recon = -max_allowed + under * 0.15;
                }
            }

            let final_val = sanitize_f32(recon);
            self.set_sample(offset, final_val);
            if final_val.abs() > self.max_peak {
                self.max_peak = final_val.abs();
            }
        }

        // De-click boundary smoothing: soften 2nd derivative jump at junction samples
        let smooth_pre = 0.08 * ym1 + 0.84 * y0 + 0.08 * self.get_sample(start_pos);
        let smooth_post = 0.08 * self.get_sample(end_pos) + 0.84 * y1 + 0.08 * yp1;
        self.set_sample(start_pos - 1, smooth_pre);
        self.set_sample(end_pos + 1, smooth_post);
    }
}

/// Stereo De-clipping and Anti-Click Engine.
pub struct DeclipEngine {
    sample_rate: i32,
    ch_left: DeclipChannel,
    ch_right: DeclipChannel,
}

impl DeclipEngine {
    pub fn new(sample_rate: i32) -> Self {
        let sr = sample_rate.max(8000);
        Self {
            sample_rate: sr,
            ch_left: DeclipChannel::new(sr),
            ch_right: DeclipChannel::new(sr),
        }
    }

    pub fn reset(&mut self) {
        self.ch_left.reset();
        self.ch_right.reset();
    }

    pub fn set_sample_rate(&mut self, sr: i32) {
        self.sample_rate = sr.max(8000);
        self.ch_left.set_sample_rate(sr);
        self.ch_right.set_sample_rate(sr);
    }

    /// Process a mono sample stream.
    #[inline]
    pub fn process_mono_sample(&mut self, input: f32, params: &DeclipParams) -> f32 {
        self.ch_left.process_sample(input, params)
    }

    /// Process an interleaved stereo sample pair.
    #[inline]
    pub fn process_stereo_sample(
        &mut self,
        in_l: f32,
        in_r: f32,
        params: &DeclipParams,
    ) -> (f32, f32) {
        let out_l = self.ch_left.process_sample(in_l, params);
        let out_r = self.ch_right.process_sample(in_r, params);
        (out_l, out_r)
    }

    /// Get aggregated telemetry data across channels.
    pub fn get_telemetry(&self) -> DeclipTelemetry {
        let events = self.ch_left.events_count + self.ch_right.events_count;
        let samples = self.ch_left.samples_count + self.ch_right.samples_count;

        let total_recent = (self.ch_left.recent_total_samples + self.ch_right.recent_total_samples)
            .max(1) as f32;
        let clip_recent = (self.ch_left.recent_clip_samples + self.ch_right.recent_clip_samples) as f32;
        let rate = (clip_recent / total_recent).clamp(0.0, 1.0);

        let avg_level = match (self.ch_left.events_count > 0, self.ch_right.events_count > 0) {
            (true, true) => 0.5 * (self.ch_left.estimated_level + self.ch_right.estimated_level),
            (true, false) => self.ch_left.estimated_level,
            (false, true) => self.ch_right.estimated_level,
            (false, false) => 1.0,
        }.max(1e-4);
        let threshold_db = (20.0 * avg_level.log10()).clamp(-60.0, 0.0);

        let peak = self.ch_left.max_peak.max(self.ch_right.max_peak);

        DeclipTelemetry {
            repaired_events_total: events,
            repaired_samples_total: samples,
            current_clip_rate: rate,
            estimated_threshold_db: threshold_db,
            max_reconstructed_peak: peak,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::PI;

    #[test]
    fn test_zero_db_clipping_repair() {
        let mut engine = DeclipEngine::new(48_000);
        let params = DeclipParams {
            enabled: true,
            ..DeclipParams::default()
        };

        // Generate a 1kHz sine wave with peak 1.4, hard-clipped at 1.0
        let freq = 1000.0;
        let sr = 48_000.0;
        let n_samples = 480;

        let mut input_signal = Vec::with_capacity(n_samples);
        for i in 0..n_samples {
            let t = i as f32 / sr;
            let raw = 1.4 * (2.0 * PI * freq * t).sin();
            let clipped = raw.clamp(-1.0, 1.0);
            input_signal.push(clipped);
        }

        let mut output_signal = Vec::with_capacity(n_samples);
        for &s in &input_signal {
            output_signal.push(engine.process_mono_sample(s, &params));
        }

        let telem = engine.get_telemetry();
        assert!(telem.repaired_events_total > 0, "Should detect and repair clipping events");
        assert!(telem.repaired_samples_total > 0, "Should reconstruct clipped samples");
        // Reconstructed peak should exceed 1.0 because original wave had peak 1.4
        assert!(telem.max_reconstructed_peak > 1.0, "Should restore lost crest above 1.0");
    }

    #[test]
    fn test_scaled_attenuated_master_clipping_repair() {
        let mut engine = DeclipEngine::new(48_000);
        let params = DeclipParams {
            enabled: true,
            sensitivity: 0.80,
            min_amplitude: 0.12,
            max_reconstruct_ratio: 1.50,
        };

        // Master that was clipped at 1.0, then lowered by -6dB (factor 0.50)
        // Peak is only 0.50, but flat top produces clicks / crackle!
        let freq = 440.0;
        let sr = 48_000.0;
        let n_samples = 480;

        let scale = 0.50_f32; // -6.02 dBFS
        let mut input_signal = Vec::with_capacity(n_samples);
        for i in 0..n_samples {
            let t = i as f32 / sr;
            let raw = 1.3 * (2.0 * PI * freq * t).sin();
            let clipped = raw.clamp(-1.0, 1.0) * scale;
            input_signal.push(clipped);
        }

        for &s in &input_signal {
            let _ = engine.process_mono_sample(s, &params);
        }

        let telem = engine.get_telemetry();
        assert!(
            telem.repaired_events_total > 0,
            "Scaled -6dB flat clips MUST be detected even though amplitude is far below 0dBFS"
        );
        // The estimated threshold should be around -6 dB
        assert!(
            telem.estimated_threshold_db < -4.0 && telem.estimated_threshold_db > -8.0,
            "Threshold should be estimated around -6dB, got {}",
            telem.estimated_threshold_db
        );
        // Peak should be reconstructed above 0.50
        assert!(
            telem.max_reconstructed_peak > 0.50,
            "Should restore peak above 0.50 plateau, got {}",
            telem.max_reconstructed_peak
        );
    }

    #[test]
    fn test_unclipped_signal_preservation() {
        let mut engine = DeclipEngine::new(48_000);
        let params = DeclipParams {
            enabled: true,
            ..DeclipParams::default()
        };

        // Clean sine wave with amplitude 0.70 (never clipped)
        let freq = 500.0;
        let sr = 48_000.0;
        let n_samples = 300;

        for i in 0..n_samples {
            let t = i as f32 / sr;
            let clean = 0.70 * (2.0 * PI * freq * t).sin();
            let _ = engine.process_mono_sample(clean, &params);
        }

        let telem = engine.get_telemetry();
        assert_eq!(
            telem.repaired_events_total, 0,
            "Clean sine wave must not trigger false positive clipping repairs"
        );
    }

    /// Quantizes to 16-bit PCM like real decoded audio.
    fn q16(x: f32) -> f32 {
        ((x * 32767.0).round().clamp(-32768.0, 32767.0)) / 32767.0
    }

    #[test]
    fn test_no_false_positives_on_clean_music_like_signals() {
        let sr = 48_000.0_f32;
        for &sens in &[0.20_f32, 0.50, 0.75, 0.98] {
            let mut engine = DeclipEngine::new(48_000);
            let params = DeclipParams {
                enabled: true,
                sensitivity: sens,
                min_amplitude: 0.12,
                max_reconstruct_ratio: 1.50,
            };
            for i in 0..(48_000 * 2) {
                let t = i as f32 / sr;
                // Bass + kick-like LF + mids + HF cymbal-like partials, never clipped (peak < 0.9)
                let s = 0.35 * (2.0 * PI * 55.0 * t).sin()
                    + 0.20 * (2.0 * PI * 110.0 * t + 0.3).sin()
                    + 0.12 * (2.0 * PI * 1250.0 * t).sin()
                    + 0.06 * (2.0 * PI * 7300.0 * t).sin()
                    + 0.04 * (2.0 * PI * 11900.0 * t + 1.1).sin();
                let _ = engine.process_mono_sample(q16(s), &params);
            }
            // Pure low tone too
            for i in 0..48_000 {
                let t = i as f32 / sr;
                let _ = engine.process_mono_sample(q16(0.6 * (2.0 * PI * 40.0 * t).sin()), &params);
            }
            let telem = engine.get_telemetry();
            assert_eq!(
                telem.repaired_events_total, 0,
                "Clean unclipped music must not trigger repairs at sensitivity {sens}"
            );
        }
    }

    #[test]
    fn test_real_clipping_still_detected_at_minimum_sensitivity() {
        let sr = 48_000.0_f32;
        let mut engine = DeclipEngine::new(48_000);
        let params = DeclipParams {
            enabled: true,
            sensitivity: 0.20,
            min_amplitude: 0.12,
            max_reconstruct_ratio: 1.50,
        };
        for i in 0..4800 {
            let t = i as f32 / sr;
            let raw = 1.5 * (2.0 * PI * 800.0 * t).sin() + 0.2 * (2.0 * PI * 3100.0 * t).sin();
            let _ = engine.process_mono_sample(q16(raw.clamp(-1.0, 1.0) * 0.7), &params);
        }
        assert!(
            engine.get_telemetry().repaired_events_total > 10,
            "Heavily clipped (then lowered -3dB) material must still be repaired at minimum sensitivity"
        );
    }

    #[test]
    fn test_nan_and_inf_resilience() {
        let mut engine = DeclipEngine::new(48_000);
        let params = DeclipParams {
            enabled: true,
            ..DeclipParams::default()
        };

        let _ = engine.process_mono_sample(f32::NAN, &params);
        let _ = engine.process_mono_sample(f32::INFINITY, &params);
        let _ = engine.process_mono_sample(f32::NEG_INFINITY, &params);
        let out = engine.process_mono_sample(0.5, &params);

        assert!(out.is_finite());
        let (out_l, out_r) = engine.process_stereo_sample(f32::NAN, 0.4, &params);
        assert!(out_l.is_finite() && out_r.is_finite());
    }

    #[test]
    fn test_isolated_click_and_crackle_suppression() {
        let mut engine = DeclipEngine::new(48_000);
        let params = DeclipParams {
            enabled: true,
            sensitivity: 0.85,
            min_amplitude: 0.10,
            max_reconstruct_ratio: 1.50,
        };

        // Smooth wave with artificially injected click/crackles
        let freq = 200.0;
        let sr = 48_000.0;
        let n_samples = 400;

        let click_idx = 60;
        let mut input_signal = Vec::with_capacity(n_samples);
        for i in 0..n_samples {
            let t = i as f32 / sr;
            let mut s = 0.45 * (2.0 * PI * freq * t).sin();
            // Inject click spike near positive crest
            if i == click_idx {
                s += 0.28; // sudden step click
            }
            input_signal.push(s);
        }

        let mut output_signal = Vec::with_capacity(n_samples);
        for &s in &input_signal {
            output_signal.push(engine.process_mono_sample(s, &params));
        }

        let telem = engine.get_telemetry();
        assert!(
            telem.repaired_events_total > 0,
            "Isolated clicks/crackles must be detected and repaired"
        );

        // Check that the repaired spike at click_idx (accounting for lookahead 48) is smoothed
        let out_click_idx = click_idx + DECLIP_LOOKAHEAD_SAMPLES;
        if out_click_idx < output_signal.len() {
            let repaired_val = output_signal[out_click_idx];
            let raw_click_val = input_signal[click_idx];
            assert!(
                (repaired_val - raw_click_val).abs() > 0.10,
                "The click spike must be smoothed toward the baseline wave (raw: {}, repaired: {})",
                raw_click_val, repaired_val
            );
        }
    }
}

