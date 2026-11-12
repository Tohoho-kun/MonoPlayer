//! High-speed Radix-2 Cooley-Tukey FFT/IFFT and STFT stereo-to-mono hop processor.

use crate::math_guard::sanitize_f32;
use std::f64::consts::PI;

pub struct FastFftEngine {
    pub size: usize,
    rev: Vec<usize>,
    cos_table: Vec<f32>,
    sin_table: Vec<f32>,
}

impl FastFftEngine {
    pub fn new(size: usize) -> Self {
        let valid_size = size.next_power_of_two().max(2);
        let bits = valid_size.trailing_zeros() as usize;
        let mut rev = vec![0usize; valid_size];
        for i in 0..valid_size {
            let mut x = i;
            let mut r = 0usize;
            for _ in 0..bits {
                r = (r << 1) | (x & 1);
                x >>= 1;
            }
            rev[i] = r;
        }

        let half = valid_size / 2;
        let mut cos_table = vec![0.0_f32; half];
        let mut sin_table = vec![0.0_f32; half];
        for i in 0..half {
            let angle = -2.0 * PI * (i as f64) / (valid_size as f64);
            cos_table[i] = angle.cos() as f32;
            sin_table[i] = angle.sin() as f32;
        }

        Self {
            size: valid_size,
            rev,
            cos_table,
            sin_table,
        }
    }

    pub fn transform(&self, real: &mut [f32], imag: &mut [f32], inverse: bool) {
        let n = self.size;
        if real.len() < n || imag.len() < n {
            return;
        }
        for i in 0..n {
            real[i] = sanitize_f32(real[i]);
            imag[i] = sanitize_f32(imag[i]);
        }
        for i in 0..n {
            let j = self.rev[i];
            if i < j {
                real.swap(i, j);
                imag.swap(i, j);
            }
        }

        let sign = if inverse { -1.0_f32 } else { 1.0_f32 };
        let mut len = 2usize;
        while len <= n {
            let half = len >> 1;
            let step = n / len;
            let mut i = 0usize;
            while i < n {
                for j in 0..half {
                    let idx = j * step;
                    let c = self.cos_table[idx];
                    let s = self.sin_table[idx] * sign;
                    let a = i + j;
                    let b = a + half;
                    let u_r = real[a];
                    let u_i = imag[a];
                    let r_b = real[b];
                    let i_b = imag[b];
                    let v_r = r_b * c - i_b * s;
                    let v_i = r_b * s + i_b * c;
                    real[a] = u_r + v_r;
                    imag[a] = u_i + v_i;
                    real[b] = u_r - v_r;
                    imag[b] = u_i - v_i;
                }
                i += len;
            }
            len <<= 1;
        }

        if inverse {
            let inv_n = 1.0 / (n as f32);
            for i in 0..n {
                real[i] = sanitize_f32(real[i] * inv_n);
                imag[i] = sanitize_f32(imag[i] * inv_n);
            }
        }
    }
}
