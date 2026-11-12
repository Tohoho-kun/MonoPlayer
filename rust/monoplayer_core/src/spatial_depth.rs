//! Stereo-Informed Z-Axis Mono Depth & Adjustable Narrow Stereo Engine in Rust.
//! Blauert directional band shaping + Haas ITDG Pre-Delay + 4 prime early reflections
//! + 3 Schroeder all-pass diffusers + 2x2 orthogonal damped FDN with subnormal/NaN guard.

use crate::math_guard::sanitize_f32;
use crate::svf::TptSvfFilter;

const PD_SIZE: usize = 8192;
const PD_MASK: usize = PD_SIZE - 1;
const ITD_SIZE: usize = 64;
const ITD_MASK: usize = ITD_SIZE - 1;

struct AllPassDiffuser {
    buf: Vec<f32>,
    len: usize,
    idx: usize,
}

impl AllPassDiffuser {
    fn new(max_len: usize) -> Self {
        let cap = max_len.max(16);
        Self {
            buf: vec![0.0; cap],
            len: max_len.max(8),
            idx: 0,
        }
    }

    fn configure_length(&mut self, new_len: usize) {
        self.len = new_len.clamp(8, self.buf.len() - 1);
        if self.idx >= self.len {
            self.idx = 0;
        }
    }

    #[inline(always)]
    fn process(&mut self, x: f32, g: f32) -> f32 {
        let delayed = self.buf[self.idx];
        let v = sanitize_f32(x - g * delayed);
        self.buf[self.idx] = v;
        self.idx += 1;
        if self.idx >= self.len {
            self.idx = 0;
        }
        sanitize_f32(delayed + g * v)
    }

    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.idx = 0;
    }
}

#[derive(Clone, Copy, Debug)]
pub struct SpatialParams {
    pub is_narrow_stereo: bool,
    pub narrow_width_percent: f32,
    pub depth_enabled: bool,
    pub depth_amount: f32,
    pub pre_delay_ms: f32,
    pub room_decay: f32,
    pub front_back_contrast: f32,
}

pub struct StereoDepthEngine {
    sr: i32,
    side_hp_filter: TptSvfFilter,
    mid_hp_filter: TptSvfFilter,
    front_presence_filter: TptSvfFilter,
    rear_presence_dip_filter: TptSvfFilter,
    rear_body_filter: TptSvfFilter,
    rear_air_lp_filter: TptSvfFilter,
    pre_delay_buf: Vec<f32>,
    pd_write: usize,
    side_itd_buf: [f32; ITD_SIZE],
    itd_write: usize,
    itd_delay_samples: usize,
    ap1: AllPassDiffuser,
    ap2: AllPassDiffuser,
    ap3: AllPassDiffuser,
    fdn_buf1: Vec<f32>,
    fdn_buf2: Vec<f32>,
    fdn_len1: usize,
    fdn_len2: usize,
    fdn_idx1: usize,
    fdn_idx2: usize,
    fdn_lp1: f32,
    fdn_lp2: f32,
    env_mid: f32,
    env_side: f32,
    env_depth_out: f32,
    smooth_depth_wet: f32,
    smooth_width: f32,
    smooth_init: bool,
    control_step: i32,
    pub current_side_ratio: f32,
    pub current_depth_level_db: f32,
}

impl StereoDepthEngine {
    pub fn new(initial_sample_rate: i32) -> Self {
        let valid_sr = initial_sample_rate.max(8000);
        let mut engine = Self {
            sr: valid_sr,
            side_hp_filter: TptSvfFilter::new(),
            mid_hp_filter: TptSvfFilter::new(),
            front_presence_filter: TptSvfFilter::new(),
            rear_presence_dip_filter: TptSvfFilter::new(),
            rear_body_filter: TptSvfFilter::new(),
            rear_air_lp_filter: TptSvfFilter::new(),
            pre_delay_buf: vec![0.0; PD_SIZE],
            pd_write: 0,
            side_itd_buf: [0.0; ITD_SIZE],
            itd_write: 0,
            itd_delay_samples: 13,
            ap1: AllPassDiffuser::new(512),
            ap2: AllPassDiffuser::new(768),
            ap3: AllPassDiffuser::new(1024),
            fdn_buf1: vec![0.0; 4096],
            fdn_buf2: vec![0.0; 4096],
            fdn_len1: 1487,
            fdn_len2: 2003,
            fdn_idx1: 0,
            fdn_idx2: 0,
            fdn_lp1: 0.0,
            fdn_lp2: 0.0,
            env_mid: 0.0,
            env_side: 0.0,
            env_depth_out: 1.0e-6,
            smooth_depth_wet: 0.0,
            smooth_width: 0.0,
            smooth_init: false,
            control_step: 0,
            current_side_ratio: 0.0,
            current_depth_level_db: -60.0,
        };
        engine.configure_for_sample_rate(valid_sr);
        engine
    }

    pub fn set_sample_rate(&mut self, new_sr: i32) {
        let valid = new_sr.max(8000);
        if valid != self.sr {
            self.sr = valid;
            self.configure_for_sample_rate(valid);
            self.reset();
        }
    }

    fn configure_for_sample_rate(&mut self, sample_rate: i32) {
        let scale = (sample_rate as f32) / 48000.0;
        self.side_hp_filter.set_high_pass(sample_rate, 180.0, 0.7071);
        self.mid_hp_filter.set_high_pass(sample_rate, 200.0, 0.7071);

        self.ap1
            .configure_length(((223.0 * scale).round() as usize).clamp(24, 500));
        self.ap2
            .configure_length(((349.0 * scale).round() as usize).clamp(32, 750));
        self.ap3
            .configure_length(((523.0 * scale).round() as usize).clamp(48, 1000));

        self.fdn_len1 = ((1487.0 * scale).round() as usize).clamp(128, self.fdn_buf1.len() - 2);
        self.fdn_len2 = ((2003.0 * scale).round() as usize).clamp(160, self.fdn_buf2.len() - 2);
        self.itd_delay_samples = ((0.00027 * (sample_rate as f32)).round() as usize).clamp(2, 48);
    }

    pub fn reset(&mut self) {
        self.side_hp_filter.reset();
        self.mid_hp_filter.reset();
        self.front_presence_filter.reset();
        self.rear_presence_dip_filter.reset();
        self.rear_body_filter.reset();
        self.rear_air_lp_filter.reset();
        self.pre_delay_buf.fill(0.0);
        self.side_itd_buf.fill(0.0);
        self.pd_write = 0;
        self.itd_write = 0;
        self.ap1.reset();
        self.ap2.reset();
        self.ap3.reset();
        self.fdn_buf1.fill(0.0);
        self.fdn_buf2.fill(0.0);
        self.fdn_idx1 = 0;
        self.fdn_idx2 = 0;
        self.fdn_lp1 = 0.0;
        self.fdn_lp2 = 0.0;
        self.env_mid = 0.0;
        self.env_side = 0.0;
        self.env_depth_out = 1.0e-6;
        self.smooth_init = false;
        self.control_step = 0;
    }

    pub fn process_sample(
        &mut self,
        mono_core: f32,
        delayed_l: f32,
        delayed_r: f32,
        params: &SpatialParams,
    ) -> (f32, f32) {
        let mc = sanitize_f32(mono_core);
        let dl = sanitize_f32(delayed_l);
        let dr = sanitize_f32(delayed_r);

        let target_wet = if params.depth_enabled {
            params.depth_amount.clamp(0.0, 1.0) * 0.68
        } else {
            0.0
        };
        let target_width = if params.is_narrow_stereo {
            (params.narrow_width_percent / 100.0).clamp(0.05, 0.80)
        } else {
            0.0
        };

        if !self.smooth_init {
            self.smooth_depth_wet = target_wet;
            self.smooth_width = target_width;
            self.smooth_init = true;
        } else {
            let alpha = 0.0025_f32;
            self.smooth_depth_wet += alpha * (target_wet - self.smooth_depth_wet);
            self.smooth_width += alpha * (target_width - self.smooth_width);
            if target_wet == 0.0 && self.smooth_depth_wet < 1.0e-5 {
                self.smooth_depth_wet = 0.0;
            }
            if target_width == 0.0 && self.smooth_width < 1.0e-5 {
                self.smooth_width = 0.0;
            }
        }

        if self.smooth_depth_wet == 0.0 && self.smooth_width == 0.0 {
            self.current_side_ratio = sanitize_f32(self.current_side_ratio * 0.995);
            self.current_depth_level_db = -60.0;
            return (mc, mc);
        }

        let raw_mid = 0.5 * (dl + dr);
        let raw_side = 0.5 * (dl - dr);
        let side_hp = self.side_hp_filter.process_mono(raw_side);
        let mono_hp = self.mid_hp_filter.process_mono(mc);

        self.env_mid = sanitize_f32(self.env_mid * 0.996 + raw_mid.abs() * 0.004);
        self.env_side = sanitize_f32(self.env_side * 0.996 + side_hp.abs() * 0.004);
        let side_dom = (self.env_side / (self.env_mid + self.env_side + 1.0e-5)).clamp(0.0, 1.0);
        self.current_side_ratio = side_dom;

        let contrast = params.front_back_contrast.clamp(0.0, 1.0);
        if (self.control_step & 15) == 0 {
            let front_db = if params.depth_enabled {
                1.75 * contrast * (1.0 - side_dom * 0.45)
            } else {
                0.0
            };
            self.front_presence_filter.set_bell(self.sr, 3150.0, 1.15, front_db);
            self.rear_presence_dip_filter.set_bell(self.sr, 3150.0, 1.20, -3.8 * contrast);
            self.rear_body_filter.set_bell(self.sr, 920.0, 0.90, 1.45 * contrast);
            self.rear_air_lp_filter
                .set_low_pass(self.sr, 8200.0 - 3800.0 * contrast, 0.7071);
        }
        self.control_step = self.control_step.wrapping_add(1);

        let front_mono = if self.smooth_depth_wet > 0.0 && contrast > 0.01 {
            self.front_presence_filter.process_mono(mc)
        } else {
            mc
        };

        let mut depth_mono = 0.0_f32;
        let mut depth_side = 0.0_f32;
        if self.smooth_depth_wet > 0.0 {
            let raw_excite = side_hp * 0.90 + mono_hp * 0.14;
            let dip = self.rear_presence_dip_filter.process_mono(raw_excite);
            let body = self.rear_body_filter.process_mono(dip);
            let recessed = self.rear_air_lp_filter.process_mono(body);

            self.pre_delay_buf[self.pd_write] = recessed;
            let base_pd = ((params.pre_delay_ms.clamp(4.0, 42.0) * 0.001 * (self.sr as f32)).round()
                as usize)
                .clamp(24, 3200);
            let decay = params.room_decay.clamp(0.10, 0.85);
            let er_span = ((self.sr as f32) * (0.008 + 0.026 * decay)).round() as usize;

            let t1 = self.pre_delay_buf[self.pd_write.wrapping_sub(base_pd) & PD_MASK];
            let t2 = self.pre_delay_buf
                [self.pd_write.wrapping_sub(base_pd + (er_span * 23) / 100) & PD_MASK];
            let t3 = self.pre_delay_buf
                [self.pd_write.wrapping_sub(base_pd + (er_span * 53) / 100) & PD_MASK];
            let t4 = self.pre_delay_buf
                [self.pd_write.wrapping_sub(base_pd + (er_span * 89) / 100) & PD_MASK];
            self.pd_write = (self.pd_write + 1) & PD_MASK;

            let early_mono = t1 * 0.46 + t2 * 0.30 + t3 * 0.20 + t4 * 0.14;
            let early_diff = (t1 - t2) * 0.34 + (t3 - t4) * 0.24;

            let s1 = self.ap1.process(early_mono, 0.52);
            let s2 = self.ap2.process(s1, 0.50);
            let diff = self.ap3.process(s2, 0.48);

            let y1 = self.fdn_buf1[self.fdn_idx1];
            let y2 = self.fdn_buf2[self.fdn_idx2];
            self.fdn_lp1 = sanitize_f32(self.fdn_lp1 + 0.36 * (y1 - self.fdn_lp1));
            self.fdn_lp2 = sanitize_f32(self.fdn_lp2 + 0.36 * (y2 - self.fdn_lp2));

            let rot1 = 0.70710677 * (self.fdn_lp1 + self.fdn_lp2);
            let rot2 = 0.70710677 * (self.fdn_lp1 - self.fdn_lp2);
            let fb = (0.16 + decay * 0.55).clamp(0.14, 0.72);

            self.fdn_buf1[self.fdn_idx1] = sanitize_f32(diff * 0.54 + rot1 * fb);
            self.fdn_buf2[self.fdn_idx2] = sanitize_f32(diff * 0.34 + rot2 * fb);
            self.fdn_idx1 += 1;
            if self.fdn_idx1 >= self.fdn_len1 {
                self.fdn_idx1 = 0;
            }
            self.fdn_idx2 += 1;
            if self.fdn_idx2 >= self.fdn_len2 {
                self.fdn_idx2 = 0;
            }

            let vocal_duck = 1.0 - 0.28 * (1.0 - side_dom) * contrast;
            depth_mono =
                early_mono * 0.62 + (diff * 0.26 + (y1 * 0.62 + y2 * 0.38) * 0.52) * vocal_duck;
            depth_side = early_diff * 0.58 + (y1 - y2) * 0.40 * vocal_duck;

            let out_abs = (depth_mono * self.smooth_depth_wet).abs();
            self.env_depth_out = sanitize_f32(self.env_depth_out * 0.997 + out_abs * 0.003).max(1.0e-6);
            self.current_depth_level_db =
                (20.0 * self.env_depth_out.max(1.0e-4).log10()).clamp(-60.0, 0.0);
        } else {
            self.current_depth_level_db = -60.0;
        }

        let mid_composite = sanitize_f32(front_mono + self.smooth_depth_wet * depth_mono);
        if self.smooth_width == 0.0 {
            (mid_composite, mid_composite)
        } else {
            self.side_itd_buf[self.itd_write] = side_hp;
            let delayed_side = self.side_itd_buf
                [self.itd_write.wrapping_sub(self.itd_delay_samples) & ITD_MASK];
            self.itd_write = (self.itd_write + 1) & ITD_MASK;
            let cohesive_side = side_hp * 0.82 + delayed_side * 0.18;
            let total_side = self.smooth_width * cohesive_side
                + self.smooth_depth_wet * self.smooth_width * depth_side * 0.65;
            (
                sanitize_f32(mid_composite + total_side),
                sanitize_f32(mid_composite - total_side),
            )
        }
    }
}
