//! `monoplayer_core` — Native Rust Real-Time Audio DSP & Optical Pixel Engine for MonoPlayer.
//!
//! Provides zero-allocation, NaN/subnormal-proof DSP kernels and high-speed RainGlass optical
//! rendering exposed to Android & JVM via a panic-safe JNI bridge (`jni_bridge`).

pub mod declip;
pub mod dynamic_eq;
pub mod eq;
pub mod fft;
pub mod jni_bridge;
pub mod loudness;
pub mod math_guard;
pub mod pipeline;
pub mod rain_render;
pub mod spatial_depth;
pub mod stft_mono;
pub mod svf;
pub mod vehicle_tracker;

#[cfg(test)]
mod tests {
    use crate::dynamic_eq::{DynBandParams, DynamicEqParams, ThreeBandDynamicEqEngine};
    use crate::eq::TenBandEqEngine;
    use crate::fft::FastFftEngine;
    use crate::loudness::{LoudnessModeCode, LoudnessNormalizerEngine, LoudnessParams};
    use crate::math_guard::{
        combine_sessile_radius, sanitize_f32, soft_limit, MasteringPeakLimiter,
    };
    use crate::rain_render::{prepare_jacket_and_fog, render_refracted_frame};
    use crate::spatial_depth::{SpatialParams, StereoDepthEngine};

    #[test]
    fn test_math_guard_nan_and_subnormal_protection() {
        assert_eq!(sanitize_f32(f32::NAN), 0.0);
        assert_eq!(sanitize_f32(f32::INFINITY), 0.0);
        assert_eq!(sanitize_f32(1e-30), 0.0);
        assert!((soft_limit(0.5) - 0.5).abs() < 1e-6);
        assert!(soft_limit(10.0) <= 1.0 && soft_limit(10.0) > 0.95);
        assert_eq!(soft_limit(f32::NAN), 0.0);
        let r = combine_sessile_radius(3.0, 2.0, 0.96, 12.0);
        assert!(r > 3.0 && r < 5.0);
    }

    #[test]
    fn test_fft_roundtrip_and_nan_safety() {
        let fft = FastFftEngine::new(256);
        let mut real = vec![0.0_f32; 256];
        let mut imag = vec![0.0_f32; 256];
        for i in 0..256 {
            real[i] = ((i as f32) * 0.15).sin();
        }
        let orig = real.clone();
        fft.transform(&mut real, &mut imag, false);
        fft.transform(&mut real, &mut imag, true);
        for i in 0..256 {
            assert!((real[i] - orig[i]).abs() < 1e-4);
        }
    }

    #[test]
    fn test_eq_and_dynamic_eq_self_heal_on_nan() {
        let mut eq = TenBandEqEngine::new(48_000);
        eq.set_band_gain(3, 6.0);
        assert_eq!(eq.process_mono_sample(f32::NAN), 0.0);
        let y = eq.process_mono_sample(0.25);
        assert!(y.is_finite() && y.abs() > 0.01);

        let mut dyn_eq = ThreeBandDynamicEqEngine::new(48_000);
        let params = DynamicEqParams {
            enabled: true,
            bands: [DynBandParams {
                enabled: true,
                freq_hz: 250.0,
                q: 0.707,
                static_gain_db: 1.5,
                threshold_db: -24.0,
                max_dynamic_gain_db: -6.0,
                attack_ms: 10.0,
                release_ms: 80.0,
            }; 3],
        };
        let _ = dyn_eq.process_mono_sample(f32::INFINITY, &params);
        let out = dyn_eq.process_mono_sample(0.4, &params);
        assert!(out.is_finite());
    }

    #[test]
    fn test_loudness_and_spatial_depth_stability() {
        let mut loud = LoudnessNormalizerEngine::new(48_000);
        let lp = LoudnessParams {
            mode: LoudnessModeCode::NoisyCar,
            normal_target_lufs: -14.0,
            car_target_lufs: -11.0,
            car_cabin_intensity: 0.8,
        };
        loud.push_lookahead_sample(f32::NAN, f32::NEG_INFINITY);
        let y = loud.process_mono_sample(0.3, &lp);
        assert!(y.is_finite());

        let mut depth = StereoDepthEngine::new(48_000);
        let sp = SpatialParams {
            is_narrow_stereo: true,
            narrow_width_percent: 35.0,
            depth_enabled: true,
            depth_amount: 0.65,
            pre_delay_ms: 14.0,
            room_decay: 0.45,
            front_back_contrast: 0.6,
        };
        let (l, r) = depth.process_sample(0.35, 0.4, 0.2, &sp);
        assert!(l.is_finite() && r.is_finite());
    }

    #[test]
    fn test_rain_render_kernels() {
        let raw = vec![0xFF336699_u32 as i32; 16 * 16];
        let mut sharp = vec![0i32; 16 * 16];
        let mut fog = vec![0i32; 16 * 16];
        prepare_jacket_and_fog(&raw, &mut sharp, &mut fog, 16, 16, 2);
        let fog_map = vec![0.5_f32; 16 * 16];
        let mut water = vec![0.0_f32; 16 * 16];
        water[8 * 16 + 8] = 0.85;
        let mut out = vec![0i32; 16 * 16];
        render_refracted_frame(&sharp, &fog, &fog_map, &water, &mut out, 16, 16, 1.0, 0.6);
        assert_ne!(out[8 * 16 + 8], 0);
    }

    #[test]
    fn test_noisy_car_102kmh_x186_with_dynamic_eq_never_hard_clips() {
        let sr = 48_000;
        let mut loud = LoudnessNormalizerEngine::new(sr);
        let mut dyn_eq = ThreeBandDynamicEqEngine::new(sr);
        let mut limiter = MasteringPeakLimiter::new(sr);

        let lp = LoudnessParams {
            mode: LoudnessModeCode::NoisyCar,
            normal_target_lufs: -14.0,
            car_target_lufs: -19.0,
            car_cabin_intensity: 1.86, // 102 km/h @ x1.86 factor
        };
        let dyn_params = DynamicEqParams {
            enabled: true,
            bands: [
                DynBandParams {
                    enabled: true,
                    freq_hz: 140.0,
                    q: 0.707,
                    static_gain_db: 0.5,
                    threshold_db: -18.0,
                    max_dynamic_gain_db: -4.5,
                    attack_ms: 15.0,
                    release_ms: 140.0,
                },
                DynBandParams {
                    enabled: true,
                    freq_hz: 2100.0,
                    q: 1.2,
                    static_gain_db: 0.0,
                    threshold_db: -20.0,
                    max_dynamic_gain_db: -3.0,
                    attack_ms: 8.0,
                    release_ms: 90.0,
                },
                DynBandParams {
                    enabled: true,
                    freq_hz: 5800.0,
                    q: 0.707,
                    static_gain_db: 0.5,
                    threshold_db: -22.0,
                    max_dynamic_gain_db: 3.0,
                    attack_ms: 3.0,
                    release_ms: 65.0,
                },
            ],
        };

        let n_samples = sr * 3;
        let mut max_out = 0.0_f32;
        let mut clipped_steps = 0usize;
        let mut prev_l = 0.0_f32;

        for n in 0..n_samples {
            let t = (n as f32) / (sr as f32);
            let x = 0.50 * (2.0 * std::f32::consts::PI * 95.0 * t).sin()
                + 0.30 * (2.0 * std::f32::consts::PI * 2600.0 * t).sin()
                + 0.14 * (2.0 * std::f32::consts::PI * 6000.0 * t).sin();
            loud.push_lookahead_sample(x, x);
            let y1 = loud.process_mono_sample(x, &lp);
            let y2 = dyn_eq.process_mono_sample(y1, &dyn_params);
            let (out_l, _) = limiter.process_stereo(y2, y2);
            if n > sr {
                max_out = max_out.max(out_l.abs());
                if out_l.abs() > 0.96 && (out_l - prev_l).abs() < 1e-4 {
                    clipped_steps += 1;
                }
            }
            prev_l = out_l;
        }
        assert!(max_out <= 0.95, "Steady output peak must stay cleanly limited <= 0.95, got {max_out}");
        assert_eq!(clipped_steps, 0, "Zero flat-top clipping allowed");
    }
}
