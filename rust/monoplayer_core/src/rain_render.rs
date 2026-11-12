//! High-speed native optical pixel kernels for RainGlassEngine:
//! 1. Wet-glass vignette + 3-pass separable O(W*H) Gaussian-approximated condensation blur ("滲み").
//! 2. Per-pixel 3D surface normals, sub-pixel bilinear Snell refraction with R/G/B chromatic
//!    dispersion, Schlick Fresnel rim darkening, lower-belly caustics, and d1^64 specular glints.

pub fn prepare_jacket_and_fog(
    raw_argb: &[i32],
    sharp_out: &mut [i32],
    fog_out: &mut [i32],
    width: usize,
    height: usize,
    blur_radius: usize,
) {
    let pixel_count = width.saturating_mul(height);
    if raw_argb.len() < pixel_count
        || sharp_out.len() < pixel_count
        || fog_out.len() < pixel_count
        || width == 0
        || height == 0
    {
        return;
    }

    let cx = (width as f32) * 0.5;
    let cy = (height as f32) * 0.5;
    let inv_max_dist_sq = 1.0 / (cx * cx + cy * cy).max(1.0);

    for y in 0..height {
        let dy = (y as f32) - cy;
        let row_off = y * width;
        for x in 0..width {
            let dx = (x as f32) - cx;
            let vignette = (1.0 - 0.28 * (dx * dx + dy * dy) * inv_max_dist_sq).clamp(0.62, 1.0);
            let c = raw_argb[row_off + x] as u32;
            let r = ((((c >> 16) & 0xFF) as f32) * 0.94 * vignette) as i32;
            let g = ((((c >> 8) & 0xFF) as f32) * 0.96 * vignette) as i32;
            let b = (((c & 0xFF) as f32) * 1.00 * vignette) as i32;
            sharp_out[row_off + x] = (0xFF_u32 << 24
                | ((r.clamp(0, 255) as u32) << 16)
                | ((g.clamp(0, 255) as u32) << 8)
                | (b.clamp(0, 255) as u32)) as i32;
        }
    }

    fog_out[..pixel_count].copy_from_slice(&sharp_out[..pixel_count]);
    let mut blur_temp = vec![0i32; pixel_count];
    let r = blur_radius.max(1);
    for _ in 0..3 {
        box_blur_horizontal(fog_out, &mut blur_temp, width, height, r);
        box_blur_vertical(&blur_temp, fog_out, width, height, r);
    }

    for i in 0..pixel_count {
        let c = fog_out[i] as u32;
        let r = ((c >> 16) & 0xFF) as i32;
        let g = ((c >> 8) & 0xFF) as i32;
        let b = (c & 0xFF) as i32;
        let luma = (r * 54 + g * 183 + b * 19) >> 8;
        let mr = ((r as f32) * 0.72 + (luma as f32) * 0.16 + 14.0) as i32;
        let mg = ((g as f32) * 0.74 + (luma as f32) * 0.16 + 17.0) as i32;
        let mb = ((b as f32) * 0.78 + (luma as f32) * 0.16 + 24.0) as i32;
        fog_out[i] = (0xFF_u32 << 24
            | ((mr.clamp(0, 255) as u32) << 16)
            | ((mg.clamp(0, 255) as u32) << 8)
            | (mb.clamp(0, 255) as u32)) as i32;
    }
}

fn box_blur_horizontal(src: &[i32], dst: &mut [i32], width: usize, height: usize, radius: usize) {
    let window = (radius * 2 + 1) as i32;
    let w_i = width as isize;
    let r_i = radius as isize;
    for y in 0..height {
        let row = y * width;
        let mut sr = 0i32;
        let mut sg = 0i32;
        let mut sb = 0i32;
        for k in -r_i..=r_i {
            let idx = k.clamp(0, w_i - 1) as usize;
            let c = src[row + idx] as u32;
            sr += ((c >> 16) & 0xFF) as i32;
            sg += ((c >> 8) & 0xFF) as i32;
            sb += (c & 0xFF) as i32;
        }
        for x in 0..width {
            dst[row + x] = (0xFF_u32 << 24
                | (((sr / window) as u32) << 16)
                | (((sg / window) as u32) << 8)
                | ((sb / window) as u32)) as i32;
            let x_i = x as isize;
            let out_idx = (x_i - r_i).clamp(0, w_i - 1) as usize;
            let in_idx = (x_i + r_i + 1).clamp(0, w_i - 1) as usize;
            let c_out = src[row + out_idx] as u32;
            let c_in = src[row + in_idx] as u32;
            sr += (((c_in >> 16) & 0xFF) as i32) - (((c_out >> 16) & 0xFF) as i32);
            sg += (((c_in >> 8) & 0xFF) as i32) - (((c_out >> 8) & 0xFF) as i32);
            sb += ((c_in & 0xFF) as i32) - ((c_out & 0xFF) as i32);
        }
    }
}

fn box_blur_vertical(src: &[i32], dst: &mut [i32], width: usize, height: usize, radius: usize) {
    let window = (radius * 2 + 1) as i32;
    let h_i = height as isize;
    let r_i = radius as isize;
    for x in 0..width {
        let mut sr = 0i32;
        let mut sg = 0i32;
        let mut sb = 0i32;
        for k in -r_i..=r_i {
            let y_idx = k.clamp(0, h_i - 1) as usize;
            let c = src[y_idx * width + x] as u32;
            sr += ((c >> 16) & 0xFF) as i32;
            sg += ((c >> 8) & 0xFF) as i32;
            sb += (c & 0xFF) as i32;
        }
        for y in 0..height {
            dst[y * width + x] = (0xFF_u32 << 24
                | (((sr / window) as u32) << 16)
                | (((sg / window) as u32) << 8)
                | ((sb / window) as u32)) as i32;
            let y_i = y as isize;
            let out_y = (y_i - r_i).clamp(0, h_i - 1) as usize;
            let in_y = (y_i + r_i + 1).clamp(0, h_i - 1) as usize;
            let c_out = src[out_y * width + x] as u32;
            let c_in = src[in_y * width + x] as u32;
            sr += (((c_in >> 16) & 0xFF) as i32) - (((c_out >> 16) & 0xFF) as i32);
            sg += (((c_in >> 8) & 0xFF) as i32) - (((c_out >> 8) & 0xFF) as i32);
            sb += ((c_in & 0xFF) as i32) - ((c_out & 0xFF) as i32);
        }
    }
}

pub fn render_refracted_frame(
    sharp_pixels: &[i32],
    fog_pixels: &[i32],
    fog_map: &[f32],
    water_height: &[f32],
    out_pixels: &mut [i32],
    width: usize,
    height: usize,
    sim_scale: f32,
    high_drive: f32,
) {
    let pixel_count = width.saturating_mul(height);
    if sharp_pixels.len() < pixel_count
        || fog_pixels.len() < pixel_count
        || fog_map.len() < pixel_count
        || water_height.len() < pixel_count
        || out_pixels.len() < pixel_count
        || width == 0
        || height == 0
    {
        return;
    }

    let l1x = -0.36_f32;
    let l1y = -0.66_f32;
    let l1z = 0.66_f32;
    let l2x = 0.44_f32;
    let l2y = -0.48_f32;
    let l2z = 0.76_f32;
    let glint_boost = (228.0 + high_drive.clamp(0.0, 1.0) * 38.0) * 1.08;
    let grad_scale = 1.95 * sim_scale;
    let edge_denom = 0.042 / sim_scale.max(1.0);

    for y in 0..height {
        let row = y * width;
        let y_prev = y.saturating_sub(1) * width;
        let y_next = (y + 1).min(height - 1) * width;
        for x in 0..width {
            let i = row + x;
            let h = water_height[i];
            if h <= 0.004 || !h.is_finite() {
                let fog = ((fog_map[i] * 256.0) as i32).clamp(0, 256) as u32;
                let inv_fog = 256 - fog;
                let cs = sharp_pixels[i] as u32;
                let cf = fog_pixels[i] as u32;
                let r = ((((cs >> 16) & 0xFF) * inv_fog + ((cf >> 16) & 0xFF) * fog) >> 8) & 0xFF;
                let g = ((((cs >> 8) & 0xFF) * inv_fog + ((cf >> 8) & 0xFF) * fog) >> 8) & 0xFF;
                let b = (((cs & 0xFF) * inv_fog + (cf & 0xFF) * fog) >> 8) & 0xFF;
                out_pixels[i] = ((0xFF_u32 << 24) | (r << 16) | (g << 8) | b) as i32;
            } else {
                let x_prev = x.saturating_sub(1);
                let x_next = (x + 1).min(width - 1);
                let dhdx = (water_height[row + x_next] - water_height[row + x_prev]) * grad_scale;
                let dhdy = (water_height[y_next + x] - water_height[y_prev + x]) * grad_scale;
                let inv_len = 1.0 / (dhdx * dhdx + dhdy * dhdy + 1.0).sqrt();
                let nx = -dhdx * inv_len;
                let ny = -dhdy * inv_len;
                let nz = inv_len;

                let refract_scale = (18.5 + h * 27.5) * sim_scale;
                let off_x = nx * refract_scale;
                let off_y = ny * refract_scale;
                let xf = x as f32;
                let yf = y as f32;
                let mut rr = sample_channel_bilinear(
                    sharp_pixels,
                    width,
                    height,
                    xf - off_x * 0.972,
                    yf - off_y * 0.972,
                    16,
                );
                let mut rg = sample_channel_bilinear(
                    sharp_pixels,
                    width,
                    height,
                    xf - off_x,
                    yf - off_y,
                    8,
                );
                let mut rb = sample_channel_bilinear(
                    sharp_pixels,
                    width,
                    height,
                    xf - off_x * 1.032,
                    yf - off_y * 1.032,
                    0,
                );

                let one_minus_cos = (1.0 - nz).clamp(0.0, 1.0);
                let fresnel = one_minus_cos * one_minus_cos * one_minus_cos;
                let rim_darken = 1.0 - fresnel * 0.56;
                rr = rr * rim_darken + fresnel * 48.0;
                rg = rg * rim_darken + fresnel * 58.0;
                rb = rb * rim_darken + fresnel * 82.0;

                if ny > 0.12 && nz > 0.52 {
                    let caustic = (ny - 0.12) * (nz - 0.52) * 1.38;
                    rr *= 1.0 + caustic;
                    rg *= 1.0 + caustic * 0.98;
                    rb *= 1.0 + caustic * 0.94;
                }

                let d1 = (nx * l1x + ny * l1y + nz * l1z).max(0.0);
                let d1_4 = d1 * d1 * d1 * d1;
                let d1_16 = d1_4 * d1_4 * d1_4 * d1_4;
                let d1_32 = d1_16 * d1_16;
                let spec1 = d1_32 * d1_32 * glint_boost;

                let d2 = (nx * l2x + ny * l2y + nz * l2z).max(0.0);
                let d2_4 = d2 * d2 * d2 * d2;
                let spec2 = d2_4 * d2_4 * d2_4 * 62.0;

                let spec_r = spec1 + spec2 * 0.82;
                let spec_g = spec1 * 0.98 + spec2 * 0.92;
                let spec_b = spec1 * 0.94 + spec2 * 1.12;

                let edge_alpha = (h / edge_denom).clamp(0.0, 1.0);
                if edge_alpha < 0.99 {
                    let fog = ((fog_map[i] * 256.0) as i32).clamp(0, 256) as u32;
                    let inv_fog = 256 - fog;
                    let cs = sharp_pixels[i] as u32;
                    let cf = fog_pixels[i] as u32;
                    let bg_r =
                        ((((cs >> 16) & 0xFF) * inv_fog + ((cf >> 16) & 0xFF) * fog) >> 8) as f32;
                    let bg_g =
                        ((((cs >> 8) & 0xFF) * inv_fog + ((cf >> 8) & 0xFF) * fog) >> 8) as f32;
                    let bg_b = (((cs & 0xFF) * inv_fog + (cf & 0xFF) * fog) >> 8) as f32;
                    rr = bg_r * (1.0 - edge_alpha) + (rr + spec_r) * edge_alpha;
                    rg = bg_g * (1.0 - edge_alpha) + (rg + spec_g) * edge_alpha;
                    rb = bg_b * (1.0 - edge_alpha) + (rb + spec_b) * edge_alpha;
                } else {
                    rr += spec_r;
                    rg += spec_g;
                    rb += spec_b;
                }

                out_pixels[i] = (0xFF_u32 << 24
                    | (((rr as i32).clamp(0, 255) as u32) << 16)
                    | (((rg as i32).clamp(0, 255) as u32) << 8)
                    | ((rb as i32).clamp(0, 255) as u32)) as i32;
            }
        }
    }
}

#[inline(always)]
fn sample_channel_bilinear(
    sharp_pixels: &[i32],
    width: usize,
    height: usize,
    fx: f32,
    fy: f32,
    shift: u32,
) -> f32 {
    let cx = fx.clamp(0.0, (width - 1) as f32);
    let cy = fy.clamp(0.0, (height - 1) as f32);
    let x0 = cx as usize;
    let y0 = cy as usize;
    let x1 = (x0 + 1).min(width - 1);
    let y1 = (y0 + 1).min(height - 1);
    let tx = cx - (x0 as f32);
    let ty = cy - (y0 as f32);
    let row0 = y0 * width;
    let row1 = y1 * width;
    let c00 = (((sharp_pixels[row0 + x0] as u32) >> shift) & 0xFF) as f32;
    let c10 = (((sharp_pixels[row0 + x1] as u32) >> shift) & 0xFF) as f32;
    let c01 = (((sharp_pixels[row1 + x0] as u32) >> shift) & 0xFF) as f32;
    let c11 = (((sharp_pixels[row1 + x1] as u32) >> shift) & 0xFF) as f32;
    let top = c00 + (c10 - c00) * tx;
    let bot = c01 + (c11 - c01) * tx;
    top + (bot - top) * ty
}
