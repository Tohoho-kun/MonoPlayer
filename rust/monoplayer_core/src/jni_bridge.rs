//! Panic-safe C-ABI JNI bridge for `com.example.monoplayer.dsp.RustNativeBridge`.
//! Uses compiler-verified JNI vtable slots (zero external crate dependencies).

use crate::declip::{DeclipEngine, DeclipParams};
use crate::dynamic_eq::{DynBandParams, DynamicEqParams, ThreeBandDynamicEqEngine};
use crate::eq::TenBandEqEngine;
use crate::fft::FastFftEngine;
use crate::loudness::{LoudnessModeCode, LoudnessNormalizerEngine, LoudnessParams};
use crate::math_guard::{combine_sessile_radius, sanitize_f32, soft_limit, MasteringPeakLimiter};
use crate::rain_render::{prepare_jacket_and_fog, render_refracted_frame};
use crate::spatial_depth::{SpatialParams, StereoDepthEngine};
use crate::stft_mono::StftMonoEngine;
use crate::vehicle_tracker::VehicleTrackerEngine;
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};

pub type JNIEnvPtr = *mut *const *const c_void;
pub type JClass = *mut c_void;
pub type JArray = *mut c_void;
pub type JFloatArray = *mut c_void;
pub type JIntArray = *mut c_void;

// Compiler-verified JNINativeInterface_ slot indices (identical across Android NDK & Desktop JDK)
const SLOT_GET_ARRAY_LENGTH: usize = 171;
const SLOT_GET_INT_ARRAY_REGION: usize = 203;
const SLOT_GET_FLOAT_ARRAY_REGION: usize = 205;
const SLOT_SET_INT_ARRAY_REGION: usize = 211;
const SLOT_SET_FLOAT_ARRAY_REGION: usize = 213;

type FnGetArrayLength = unsafe extern "system" fn(JNIEnvPtr, JArray) -> i32;
type FnGetIntArrayRegion = unsafe extern "system" fn(JNIEnvPtr, JIntArray, i32, i32, *mut i32);
type FnGetFloatArrayRegion = unsafe extern "system" fn(JNIEnvPtr, JFloatArray, i32, i32, *mut f32);
type FnSetIntArrayRegion = unsafe extern "system" fn(JNIEnvPtr, JIntArray, i32, i32, *const i32);
type FnSetFloatArrayRegion =
    unsafe extern "system" fn(JNIEnvPtr, JFloatArray, i32, i32, *const f32);

#[inline]
unsafe fn jni_array_len(env: JNIEnvPtr, arr: JArray) -> usize {
    if env.is_null() || arr.is_null() {
        return 0;
    }
    let vtable = *env;
    let func: FnGetArrayLength = std::mem::transmute(*vtable.add(SLOT_GET_ARRAY_LENGTH));
    func(env, arr).max(0) as usize
}

#[inline]
unsafe fn jni_get_f32(env: JNIEnvPtr, arr: JFloatArray, dst: &mut [f32]) -> usize {
    let n = jni_array_len(env, arr).min(dst.len());
    if n > 0 {
        let vtable = *env;
        let func: FnGetFloatArrayRegion =
            std::mem::transmute(*vtable.add(SLOT_GET_FLOAT_ARRAY_REGION));
        func(env, arr, 0, n as i32, dst.as_mut_ptr());
    }
    n
}

#[inline]
unsafe fn jni_set_f32(env: JNIEnvPtr, arr: JFloatArray, src: &[f32]) {
    let n = jni_array_len(env, arr).min(src.len());
    if n > 0 {
        let vtable = *env;
        let func: FnSetFloatArrayRegion =
            std::mem::transmute(*vtable.add(SLOT_SET_FLOAT_ARRAY_REGION));
        func(env, arr, 0, n as i32, src.as_ptr());
    }
}

#[inline]
unsafe fn jni_get_i32(env: JNIEnvPtr, arr: JIntArray, dst: &mut [i32]) -> usize {
    let n = jni_array_len(env, arr).min(dst.len());
    if n > 0 {
        let vtable = *env;
        let func: FnGetIntArrayRegion = std::mem::transmute(*vtable.add(SLOT_GET_INT_ARRAY_REGION));
        func(env, arr, 0, n as i32, dst.as_mut_ptr());
    }
    n
}

#[inline]
unsafe fn jni_set_i32(env: JNIEnvPtr, arr: JIntArray, src: &[i32]) {
    let n = jni_array_len(env, arr).min(src.len());
    if n > 0 {
        let vtable = *env;
        let func: FnSetIntArrayRegion = std::mem::transmute(*vtable.add(SLOT_SET_INT_ARRAY_REGION));
        func(env, arr, 0, n as i32, src.as_ptr());
    }
}

pub struct FftHolder {
    engine: FastFftEngine,
    scratch_r: Vec<f32>,
    scratch_i: Vec<f32>,
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeFftCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    size: i32,
) -> i64 {
    catch_unwind(|| {
        let n = (size.max(2) as usize).next_power_of_two();
        let holder = Box::new(FftHolder {
            engine: FastFftEngine::new(n),
            scratch_r: vec![0.0; n],
            scratch_i: vec![0.0; n],
        });
        Box::into_raw(holder) as i64
    })
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeFftDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut FftHolder));
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeFftTransform(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    real_arr: JFloatArray,
    imag_arr: JFloatArray,
    inverse: u8,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let h = &mut *(handle as *mut FftHolder);
            let n = h.engine.size;
            if jni_get_f32(env, real_arr, &mut h.scratch_r[..n]) == n
                && jni_get_f32(env, imag_arr, &mut h.scratch_i[..n]) == n
            {
                h.engine
                    .transform(&mut h.scratch_r[..n], &mut h.scratch_i[..n], inverse != 0);
                jni_set_f32(env, real_arr, &h.scratch_r[..n]);
                jni_set_f32(env, imag_arr, &h.scratch_i[..n]);
            }
        }
    }));
}

// --- 10-Band Equalizer JNI ---

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    sample_rate: i32,
) -> i64 {
    catch_unwind(|| Box::into_raw(Box::new(TenBandEqEngine::new(sample_rate))) as i64).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut TenBandEqEngine));
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqSetSampleRate(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    sr: i32,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut TenBandEqEngine)).set_sample_rate(sr);
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqSetBandGain(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    band_index: i32,
    gain_db: f32,
) {
    let _ = catch_unwind(|| {
        if handle != 0 && (0..10).contains(&band_index) {
            unsafe {
                (&mut *(handle as *mut TenBandEqEngine)).set_band_gain(band_index as usize, gain_db);
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqSetAllBands(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    gains_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                let mut gains = [0.0_f32; 10];
                let n = jni_get_f32(env, gains_arr, &mut gains);
                (&mut *(handle as *mut TenBandEqEngine)).set_all_bands(&gains[..n]);
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqResetState(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut TenBandEqEngine)).reset_state();
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqProcessMono(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    x: f32,
) -> f32 {
    catch_unwind(|| {
        if handle == 0 {
            return sanitize_f32(x);
        }
        unsafe { (&mut *(handle as *mut TenBandEqEngine)).process_mono_sample(x) }
    })
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeEqProcessStereo(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    in_l: f32,
    in_r: f32,
    out_pair_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let (yl, yr) = (&mut *(handle as *mut TenBandEqEngine)).process_stereo_sample(in_l, in_r);
            jni_set_f32(env, out_pair_arr, &[yl, yr]);
        }
    }));
}

// --- 3-Band Dynamic EQ JNI ---

fn unpack_dyn_eq_params(packed: &[f32; 25]) -> DynamicEqParams {
    let mut bands = [DynBandParams {
        enabled: true,
        freq_hz: 100.0,
        q: 0.7071,
        static_gain_db: 0.0,
        threshold_db: -20.0,
        max_dynamic_gain_db: 0.0,
        attack_ms: 15.0,
        release_ms: 120.0,
    }; 3];
    for i in 0..3 {
        let b = 1 + i * 8;
        bands[i] = DynBandParams {
            enabled: packed[b] > 0.5,
            freq_hz: packed[b + 1],
            q: packed[b + 2],
            static_gain_db: packed[b + 3],
            threshold_db: packed[b + 4],
            max_dynamic_gain_db: packed[b + 5],
            attack_ms: packed[b + 6],
            release_ms: packed[b + 7],
        };
    }
    DynamicEqParams {
        enabled: packed[0] > 0.5,
        bands,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDynEqCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    sample_rate: i32,
) -> i64 {
    catch_unwind(|| Box::into_raw(Box::new(ThreeBandDynamicEqEngine::new(sample_rate))) as i64)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDynEqDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut ThreeBandDynamicEqEngine));
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDynEqSetSampleRate(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    sr: i32,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut ThreeBandDynamicEqEngine)).set_sample_rate(sr);
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDynEqReset(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut ThreeBandDynamicEqEngine)).reset();
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDynEqProcessMono(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    input: f32,
    packed_cfg_arr: JFloatArray,
    telemetry_out6_arr: JFloatArray,
) -> f32 {
    catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return sanitize_f32(input);
        }
        unsafe {
            let mut packed = [0.0_f32; 25];
            jni_get_f32(env, packed_cfg_arr, &mut packed);
            let params = unpack_dyn_eq_params(&packed);
            let eng = &mut *(handle as *mut ThreeBandDynamicEqEngine);
            let out = eng.process_mono_sample(input, &params);
            let det = eng.detected_bands_db();
            let dlt = eng.dynamic_gain_deltas_db();
            jni_set_f32(
                env,
                telemetry_out6_arr,
                &[det[0], det[1], det[2], dlt[0], dlt[1], dlt[2]],
            );
            out
        }
    }))
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDynEqProcessStereo(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    in_l: f32,
    in_r: f32,
    packed_cfg_arr: JFloatArray,
    out_pair_arr: JFloatArray,
    telemetry_out6_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let mut packed = [0.0_f32; 25];
            jni_get_f32(env, packed_cfg_arr, &mut packed);
            let params = unpack_dyn_eq_params(&packed);
            let eng = &mut *(handle as *mut ThreeBandDynamicEqEngine);
            let (yl, yr) = eng.process_stereo_sample(in_l, in_r, &params);
            jni_set_f32(env, out_pair_arr, &[yl, yr]);
            let det = eng.detected_bands_db();
            let dlt = eng.dynamic_gain_deltas_db();
            jni_set_f32(
                env,
                telemetry_out6_arr,
                &[det[0], det[1], det[2], dlt[0], dlt[1], dlt[2]],
            );
        }
    }));
}

// --- Realtime Loudness Normalizer JNI ---

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLoudnessCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    sample_rate: i32,
) -> i64 {
    catch_unwind(|| Box::into_raw(Box::new(LoudnessNormalizerEngine::new(sample_rate))) as i64)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLoudnessDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut LoudnessNormalizerEngine));
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLoudnessSetSampleRate(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    sr: i32,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut LoudnessNormalizerEngine)).set_sample_rate(sr);
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLoudnessReset(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut LoudnessNormalizerEngine)).reset_for_new_track();
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLoudnessPushLookahead(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    raw_l: f32,
    raw_r: f32,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut LoudnessNormalizerEngine)).push_lookahead_sample(raw_l, raw_r);
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLoudnessProcessMono(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    input: f32,
    mode_code: i32,
    normal_target_lufs: f32,
    car_target_lufs: f32,
    car_cabin_intensity: f32,
    telemetry_out4_arr: JFloatArray,
) -> f32 {
    catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return sanitize_f32(input);
        }
        unsafe {
            let params = LoudnessParams {
                mode: LoudnessModeCode::from_i32(mode_code),
                normal_target_lufs,
                car_target_lufs,
                car_cabin_intensity,
            };
            let eng = &mut *(handle as *mut LoudnessNormalizerEngine);
            let out = eng.process_mono_sample(input, &params);
            jni_set_f32(
                env,
                telemetry_out4_arr,
                &[
                    eng.measured_lufs,
                    eng.applied_gain_db,
                    if eng.is_gate_frozen { 1.0 } else { 0.0 },
                    eng.dynamic_clarity_boost_db,
                    eng.last_bass_punch_db,
                ],
            );
            out
        }
    }))
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLoudnessProcessStereo(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    in_l: f32,
    in_r: f32,
    mode_code: i32,
    normal_target_lufs: f32,
    car_target_lufs: f32,
    car_cabin_intensity: f32,
    out_pair_arr: JFloatArray,
    telemetry_out4_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let params = LoudnessParams {
                mode: LoudnessModeCode::from_i32(mode_code),
                normal_target_lufs,
                car_target_lufs,
                car_cabin_intensity,
            };
            let eng = &mut *(handle as *mut LoudnessNormalizerEngine);
            let (yl, yr) = eng.process_stereo_sample(in_l, in_r, &params);
            jni_set_f32(env, out_pair_arr, &[yl, yr]);
            jni_set_f32(
                env,
                telemetry_out4_arr,
                &[
                    eng.measured_lufs,
                    eng.applied_gain_db,
                    if eng.is_gate_frozen { 1.0 } else { 0.0 },
                    eng.dynamic_clarity_boost_db,
                    eng.last_bass_punch_db,
                ],
            );
        }
    }));
}

// --- Stereo Depth & Narrow Stereo JNI ---

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDepthCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    sample_rate: i32,
) -> i64 {
    catch_unwind(|| Box::into_raw(Box::new(StereoDepthEngine::new(sample_rate))) as i64)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDepthDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut StereoDepthEngine));
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDepthSetSampleRate(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    sr: i32,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut StereoDepthEngine)).set_sample_rate(sr);
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDepthReset(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut StereoDepthEngine)).reset();
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDepthProcessSample(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    mono_core: f32,
    delayed_l: f32,
    delayed_r: f32,
    is_narrow_stereo: u8,
    narrow_width_percent: f32,
    depth_enabled: u8,
    depth_amount: f32,
    pre_delay_ms: f32,
    room_decay: f32,
    front_back_contrast: f32,
    out_pair_and_telemetry4_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let params = SpatialParams {
                is_narrow_stereo: is_narrow_stereo != 0,
                narrow_width_percent,
                depth_enabled: depth_enabled != 0,
                depth_amount,
                pre_delay_ms,
                room_decay,
                front_back_contrast,
            };
            let eng = &mut *(handle as *mut StereoDepthEngine);
            let (yl, yr) = eng.process_sample(mono_core, delayed_l, delayed_r, &params);
            jni_set_f32(
                env,
                out_pair_and_telemetry4_arr,
                &[yl, yr, eng.current_side_ratio, eng.current_depth_level_db],
            );
        }
    }));
}

// --- RainGlass Optical Pixel Renderer JNI ---

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeRainPrepareJacketAndFog(
    env: JNIEnvPtr,
    _cls: JClass,
    raw_argb_arr: JIntArray,
    sharp_out_arr: JIntArray,
    fog_out_arr: JIntArray,
    width: i32,
    height: i32,
    blur_radius: i32,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let w = width.max(0) as usize;
        let h = height.max(0) as usize;
        let pixel_count = w.saturating_mul(h);
        if pixel_count == 0 || pixel_count > 4096 * 4096 {
            return;
        }
        unsafe {
            let mut raw = vec![0i32; pixel_count];
            let mut sharp = vec![0i32; pixel_count];
            let mut fog = vec![0i32; pixel_count];
            if jni_get_i32(env, raw_argb_arr, &mut raw) < pixel_count {
                return;
            }
            prepare_jacket_and_fog(&raw, &mut sharp, &mut fog, w, h, blur_radius.max(1) as usize);
            jni_set_i32(env, sharp_out_arr, &sharp);
            jni_set_i32(env, fog_out_arr, &fog);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeRainRenderRefractedFrame(
    env: JNIEnvPtr,
    _cls: JClass,
    sharp_arr: JIntArray,
    fog_arr: JIntArray,
    fog_map_arr: JFloatArray,
    water_height_arr: JFloatArray,
    out_pixels_arr: JIntArray,
    width: i32,
    height: i32,
    sim_scale: f32,
    high_drive: f32,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let w = width.max(0) as usize;
        let h = height.max(0) as usize;
        let pixel_count = w.saturating_mul(h);
        if pixel_count == 0 || pixel_count > 4096 * 4096 {
            return;
        }
        unsafe {
            let mut sharp = vec![0i32; pixel_count];
            let mut fog = vec![0i32; pixel_count];
            let mut fog_map = vec![0.0_f32; pixel_count];
            let mut water_h = vec![0.0_f32; pixel_count];
            let mut out = vec![0i32; pixel_count];
            if jni_get_i32(env, sharp_arr, &mut sharp) < pixel_count
                || jni_get_i32(env, fog_arr, &mut fog) < pixel_count
                || jni_get_f32(env, fog_map_arr, &mut fog_map) < pixel_count
                || jni_get_f32(env, water_height_arr, &mut water_h) < pixel_count
            {
                return;
            }
            render_refracted_frame(
                &sharp,
                &fog,
                &fog_map,
                &water_h,
                &mut out,
                w,
                h,
                sim_scale,
                high_drive,
            );
            jni_set_i32(env, out_pixels_arr, &out);
        }
    }));
}

// --- MathGuard & Utility JNI ---

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeSoftLimit(
    _env: JNIEnvPtr,
    _cls: JClass,
    x: f32,
) -> f32 {
    catch_unwind(|| soft_limit(x)).unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeSanitizeFloat(
    _env: JNIEnvPtr,
    _cls: JClass,
    x: f32,
) -> f32 {
    catch_unwind(|| sanitize_f32(x)).unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeCombineSessileRadius(
    _env: JNIEnvPtr,
    _cls: JClass,
    r1: f32,
    r2: f32,
    retention: f32,
    max_r: f32,
) -> f32 {
    catch_unwind(|| combine_sessile_radius(r1, r2, retention, max_r)).unwrap_or(0.0)
}

// --- Mastering Peak Limiter JNI ---

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLimiterCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    sample_rate: i32,
) -> i64 {
    catch_unwind(|| Box::into_raw(Box::new(MasteringPeakLimiter::new(sample_rate))) as i64)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLimiterDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut MasteringPeakLimiter));
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLimiterSetSampleRate(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    sample_rate: i32,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut MasteringPeakLimiter)).set_sample_rate(sample_rate);
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLimiterReset(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut MasteringPeakLimiter)).reset();
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeLimiterProcessStereo(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    in_l: f32,
    in_r: f32,
    out_pair_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let eng = &mut *(handle as *mut MasteringPeakLimiter);
            let (yl, yr) = eng.process_stereo(in_l, in_r);
            jni_set_f32(env, out_pair_arr, &[yl, yr, eng.last_gain_reduction_db]);
        }
    }));
}

// --- Anti-Clipping Reconstruction & De-Clicking JNI ---

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDeclipCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    sample_rate: i32,
) -> i64 {
    catch_unwind(|| Box::into_raw(Box::new(DeclipEngine::new(sample_rate))) as i64).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDeclipDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut DeclipEngine));
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDeclipSetSampleRate(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    sr: i32,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut DeclipEngine)).set_sample_rate(sr);
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDeclipReset(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(|| {
        if handle != 0 {
            unsafe {
                (&mut *(handle as *mut DeclipEngine)).reset();
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDeclipProcessMono(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    input: f32,
    enabled: u8,
    sensitivity: f32,
    min_amplitude: f32,
    max_reconstruct_ratio: f32,
    telemetry_out4_arr: JFloatArray,
) -> f32 {
    catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return sanitize_f32(input);
        }
        unsafe {
            let params = DeclipParams {
                enabled: enabled != 0,
                sensitivity,
                min_amplitude,
                max_reconstruct_ratio,
            };
            let eng = &mut *(handle as *mut DeclipEngine);
            let out = eng.process_mono_sample(input, &params);
            let telem = eng.get_telemetry();
            jni_set_f32(
                env,
                telemetry_out4_arr,
                &[
                    telem.current_clip_rate,
                    telem.repaired_events_total as f32,
                    telem.estimated_threshold_db,
                    telem.max_reconstructed_peak,
                ],
            );
            out
        }
    }))
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeDeclipProcessStereo(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    in_l: f32,
    in_r: f32,
    enabled: u8,
    sensitivity: f32,
    min_amplitude: f32,
    max_reconstruct_ratio: f32,
    out_pair_arr: JFloatArray,
    telemetry_out4_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let params = DeclipParams {
                enabled: enabled != 0,
                sensitivity,
                min_amplitude,
                max_reconstruct_ratio,
            };
            let eng = &mut *(handle as *mut DeclipEngine);
            let (yl, yr) = eng.process_stereo_sample(in_l, in_r, &params);
            jni_set_f32(env, out_pair_arr, &[yl, yr]);
            let telem = eng.get_telemetry();
            jni_set_f32(
                env,
                telemetry_out4_arr,
                &[
                    telem.current_clip_rate,
                    telem.repaired_events_total as f32,
                    telem.estimated_threshold_db,
                    telem.max_reconstructed_peak,
                ],
            );
        }
    }));
}

// =========================================================================
// Vehicle Speed & Tunnel Dead-Reckoning Engine
// =========================================================================

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| {
        Box::into_raw(Box::new(VehicleTrackerEngine::new())) as i64
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut VehicleTrackerEngine));
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerReset(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                let tracker = &mut *(handle as *mut VehicleTrackerEngine);
                tracker.reset();
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerOnGps(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    speed_kmh: f32,
    out2_arr: JFloatArray,
) -> f32 {
    catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return 0.0_f32;
        }
        unsafe {
            let tracker = &mut *(handle as *mut VehicleTrackerEngine);
            let (smoothed, src) = tracker.on_gps_speed(speed_kmh);
            jni_set_f32(env, out2_arr, &[smoothed, src as f32]);
            smoothed
        }
    }))
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerOnSensor(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    ax: f32,
    ay: f32,
    az: f32,
    dt_sec: f32,
    is_gps_lost: u8,
    out2_arr: JFloatArray,
) -> f32 {
    catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return 0.0_f32;
        }
        unsafe {
            let tracker = &mut *(handle as *mut VehicleTrackerEngine);
            let (smoothed, src) = tracker.on_sensor_accelerometer(ax, ay, az, dt_sec, is_gps_lost != 0);
            jni_set_f32(env, out2_arr, &[smoothed, src as f32]);
            smoothed
        }
    }))
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerOnImuSample(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    ax: f32,
    ay: f32,
    az: f32,
    gx: f32,
    gy: f32,
    gz: f32,
    wx: f32,
    wy: f32,
    wz: f32,
    has_gyro: u8,
    dt_sec: f32,
    is_gps_lost: u8,
    out5_arr: JFloatArray,
) -> f32 {
    catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return 0.0_f32;
        }
        unsafe {
            let tracker = &mut *(handle as *mut VehicleTrackerEngine);
            let out = tracker.on_imu_sample(
                ax,
                ay,
                az,
                gx,
                gy,
                gz,
                wx,
                wy,
                wz,
                has_gyro != 0,
                dt_sec,
                is_gps_lost != 0,
            );
            jni_set_f32(
                env,
                out5_arr,
                &[
                    out.smoothed_speed_kmh,
                    out.source_code as f32,
                    if out.is_forward_calibrated { 1.0 } else { 0.0 },
                    out.a_fwd,
                    out.a_lat,
                ],
            );
            out.smoothed_speed_kmh
        }
    }))
    .unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerCalcIntensity(
    _env: JNIEnvPtr,
    _cls: JClass,
    speed_kmh: f32,
    speed_ref_kmh: f32,
    compensation_factor: f32,
) -> f32 {
    VehicleTrackerEngine::calculate_intensity(speed_kmh, speed_ref_kmh, compensation_factor)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeVehicleTrackerCalcAcousticBoosts(
    env: JNIEnvPtr,
    _cls: JClass,
    intensity: f32,
    out3_arr: JFloatArray,
) {
    let (punch, clarity, trim) = VehicleTrackerEngine::calculate_acoustic_boosts(intensity);
    unsafe {
        jni_set_f32(env, out3_arr, &[punch, clarity, trim]);
    }
}

// =========================================================================
// STFT Mono & Visualizer Engine
// =========================================================================

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeStftMonoCreate(
    _env: JNIEnvPtr,
    _cls: JClass,
    sample_rate: i32,
) -> i64 {
    catch_unwind(AssertUnwindSafe(|| {
        Box::into_raw(Box::new(StftMonoEngine::new(sample_rate.max(8000) as u32))) as i64
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeStftMonoDestroy(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut StftMonoEngine));
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeStftMonoSetSampleRate(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    sample_rate: i32,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                let eng = &mut *(handle as *mut StftMonoEngine);
                eng.set_sample_rate(sample_rate.max(8000) as u32);
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeStftMonoReset(
    _env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle != 0 {
            unsafe {
                let eng = &mut *(handle as *mut StftMonoEngine);
                eng.reset();
            }
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeStftMonoProcessHop(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    ring_l_arr: JFloatArray,
    ring_r_arr: JFloatArray,
    algo_code: i32,
    blend: f32,
    crossover_freq: f32,
    out_ola_time_arr: JFloatArray,
    out_ola_norm_arr: JFloatArray,
    vis_spec32_arr: JFloatArray,
    vis_naive32_arr: JFloatArray,
    metrics2_arr: JFloatArray,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let mut ring_l = [0.0_f32; 2048];
            let mut ring_r = [0.0_f32; 2048];
            let mut out_time = [0.0_f32; 2048];
            let mut out_norm = [0.0_f32; 2048];
            let mut vis_spec = [0.0_f32; 32];
            let mut vis_naive = [0.0_f32; 32];
            let mut metrics = [0.0_f32; 2];

            jni_get_f32(env, ring_l_arr, &mut ring_l);
            jni_get_f32(env, ring_r_arr, &mut ring_r);

            let eng = &mut *(handle as *mut StftMonoEngine);
            eng.process_stft_hop(
                &ring_l,
                &ring_r,
                algo_code,
                blend,
                crossover_freq,
                &mut out_time,
                &mut out_norm,
                &mut vis_spec,
                &mut vis_naive,
                &mut metrics,
            );

            jni_set_f32(env, out_ola_time_arr, &out_time);
            jni_set_f32(env, out_ola_norm_arr, &out_norm);
            jni_set_f32(env, vis_spec32_arr, &vis_spec);
            jni_set_f32(env, vis_naive32_arr, &vis_naive);
            jni_set_f32(env, metrics2_arr, &metrics);
        }
    }));
}

#[no_mangle]
pub extern "system" fn Java_com_example_monoplayer_dsp_RustNativeBridge_nativeStftMonoProcessPcaBlock(
    env: JNIEnvPtr,
    _cls: JClass,
    handle: i64,
    dl_arr: JFloatArray,
    dr_arr: JFloatArray,
    out_mono_arr: JFloatArray,
    blend: f32,
    crossover_hz: f32,
    sample_rate: i32,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if handle == 0 {
            return;
        }
        unsafe {
            let len = jni_array_len(env, dl_arr);
            if len == 0 {
                return;
            }
            let mut dl_buf = vec![0.0_f32; len];
            let mut dr_buf = vec![0.0_f32; len];
            let mut out_buf = vec![0.0_f32; len];

            jni_get_f32(env, dl_arr, &mut dl_buf);
            jni_get_f32(env, dr_arr, &mut dr_buf);

            let eng = &mut *(handle as *mut StftMonoEngine);
            eng.process_dynamic_pca_block(
                &dl_buf,
                &dr_buf,
                &mut out_buf,
                blend,
                crossover_hz,
                sample_rate.max(8000) as u32,
            );

            jni_set_f32(env, out_mono_arr, &out_buf);
        }
    }));
}
