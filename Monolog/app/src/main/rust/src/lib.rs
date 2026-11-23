#[cfg(any(target_os = "android", test))]
mod lexicon;
#[cfg(any(target_os = "android", test))]
mod phrases;

const SAMPLE_COUNT: usize = 24;
const MAX_WORD_LENGTH: usize = 24;

#[derive(Clone, Copy, Default)]
struct Point(f32, f32);

impl Point {
    fn squared_distance(self, other: Self) -> f32 {
        (self.0 - other.0).powi(2) + (self.1 - other.1).powi(2)
    }
}

fn sample(path: &[Point]) -> Option<([Point; SAMPLE_COUNT], f32)> {
    if path.len() < 2 {
        return None;
    }
    let length = path
        .windows(2)
        .map(|pair| pair[0].squared_distance(pair[1]).sqrt())
        .sum::<f32>();
    if !length.is_finite() || length < 0.55 {
        return None;
    }
    let mut result = [Point::default(); SAMPLE_COUNT];
    let mut segment = 0;
    let mut elapsed = 0.0;
    let mut span = path[0].squared_distance(path[1]).sqrt();
    for (index, out) in result.iter_mut().enumerate() {
        let target = index as f32 * length / (SAMPLE_COUNT - 1) as f32;
        while segment + 2 < path.len() && elapsed + span <= target {
            elapsed += span;
            segment += 1;
            span = path[segment].squared_distance(path[segment + 1]).sqrt();
        }
        let start = path[segment];
        let end = path[segment + 1];
        let ratio = if span == 0.0 {
            0.0
        } else {
            ((target - elapsed) / span).clamp(0.0, 1.0)
        };
        *out = Point(
            start.0 + (end.0 - start.0) * ratio,
            start.1 + (end.1 - start.1) * ratio,
        );
    }
    Some((result, length))
}

#[cfg(any(target_os = "android", test))]
fn pcm_samples(bytes: &[u8]) -> impl Iterator<Item = f32> + '_ {
    bytes
        .chunks_exact(2)
        .map(|pair| f32::from(i16::from_le_bytes([pair[0], pair[1]])) / 32768.0)
}

#[cfg(any(target_os = "android", test))]
fn pcm_rms(bytes: &[u8]) -> f32 {
    let count = (bytes.len() / 2).max(1) as f32;
    (pcm_samples(bytes)
        .map(|sample| sample * sample)
        .sum::<f32>()
        / count)
        .sqrt()
}

#[cfg(target_os = "android")]
fn phonetic_key(text: &str) -> String {
    let letters: Vec<_> = text
        .bytes()
        .map(|b| b.to_ascii_lowercase())
        .filter(u8::is_ascii_lowercase)
        .take(64)
        .collect();
    let mut result = String::new();
    let mut i = 0;
    while i < letters.len() {
        let rest = &letters[i..];
        let (symbol, width) = match rest {
            b"gue" => ('g', 3),
            [b'p', b'h', ..] => ('f', 2),
            [b'c', b'k', ..] => ('k', 2),
            [b's', b'h', ..] => ('S', 2),
            [b'c', b'h', ..] => ('C', 2),
            [b'n', b'g', ..] => ('N', 2),
            [b'i', b'g', b'h', ..] => ('i', 3),
            [b'c', ..] => ('k', 1),
            [letter, ..] => (*letter as char, 1),
            [] => break,
        };
        if !result.ends_with(symbol) {
            result.push(symbol);
        }
        i += width;
    }
    result
}

#[cfg(target_os = "android")]
mod android {
    use super::{lexicon, pcm_rms, pcm_samples, phonetic_key, phrases, Point};
    use jni::objects::{JByteArray, JFloatArray, JObject, JString};
    use jni::sys::{jboolean, jfloat, jfloatArray, jint, jstring};
    use jni::JNIEnv;
    use std::ptr::null_mut;

    fn points(
        env: &JNIEnv<'_>,
        input: JFloatArray<'_>,
        max: usize,
    ) -> jni::errors::Result<Vec<Point>> {
        let len = env.get_array_length(&input)? as usize;
        if len % 2 != 0 || len > 2 * max {
            return Ok(vec![]);
        }
        let mut raw = [0.0; 512];
        env.get_float_array_region(input, 0, &mut raw[..len])?;
        Ok(raw[..len]
            .chunks_exact(2)
            .map(|p| Point(p[0], p[1]))
            .collect())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_notokens_monolog_ime_NativeTyping_rankNext(
        mut env: JNIEnv<'_>,
        _: JObject<'_>,
        mode: jint,
        context: JString<'_>,
    ) -> jstring {
        let mut compute = || -> jni::errors::Result<jstring> {
            let text: String = env.get_string(&context)?.into();
            Ok(env
                .new_string(phrases::next(mode as usize, &text))?
                .into_raw())
        };
        compute().unwrap_or(null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_notokens_monolog_ime_NativeTyping_decodePcm(
        env: JNIEnv<'_>,
        _: JObject<'_>,
        input: JByteArray<'_>,
    ) -> jfloatArray {
        let compute = || -> jni::errors::Result<jfloatArray> {
            let pcm = env.convert_byte_array(&input)?;
            let samples: Vec<_> = pcm_samples(&pcm).collect();
            let array = env.new_float_array(samples.len() as i32)?;
            env.set_float_array_region(&array, 0, &samples)?;
            Ok(array.into_raw())
        };
        compute().unwrap_or(null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_notokens_monolog_ime_NativeTyping_measurePcm(
        env: JNIEnv<'_>,
        _: JObject<'_>,
        input: JByteArray<'_>,
        count: jint,
    ) -> jfloat {
        env.convert_byte_array(&input).map_or(0.0, |pcm| {
            pcm_rms(&pcm[..(count.max(0) as usize).min(pcm.len())])
        })
    }

    #[no_mangle]
    pub extern "system" fn Java_com_notokens_monolog_ime_NativeTyping_pronunciationKey(
        mut env: JNIEnv<'_>,
        _: JObject<'_>,
        input: JString<'_>,
    ) -> jstring {
        let mut compute = || -> jni::errors::Result<jstring> {
            let text: String = env.get_string(&input)?.into();
            Ok(env.new_string(phonetic_key(&text))?.into_raw())
        };
        compute().unwrap_or(null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_notokens_monolog_ime_NativeTyping_installLexicon(
        mut env: JNIEnv<'_>,
        _: JObject<'_>,
        mode: jint,
        corpus: JString<'_>,
        preferred: JString<'_>,
        secondary: JString<'_>,
    ) -> jboolean {
        let mut load = || -> jni::errors::Result<bool> {
            let corpus: String = env.get_string(&corpus)?.into();
            let preferred: String = env.get_string(&preferred)?.into();
            let secondary: String = env.get_string(&secondary)?.into();
            Ok(lexicon::install(
                mode as usize,
                &corpus,
                &preferred,
                &secondary,
            ))
        };
        load().unwrap_or(false) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_notokens_monolog_ime_NativeTyping_rankSwipe(
        mut env: JNIEnv<'_>,
        _: JObject<'_>,
        mode: jint,
        path: JFloatArray<'_>,
        layout: JFloatArray<'_>,
        context: JString<'_>,
        before: JString<'_>,
    ) -> jstring {
        let compute = || -> jni::errors::Result<jstring> {
            let path = points(&env, path, 256)?;
            let layout = points(&env, layout, 27)?;
            let context: String = env.get_string(&context)?.into();
            let before: String = env.get_string(&before)?.into();
            let result = lexicon::swipe(mode as usize, &path, &layout, &context, &before);
            result.map_or(Ok(null_mut()), |text| Ok(env.new_string(text)?.into_raw()))
        };
        compute().unwrap_or(null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_notokens_monolog_ime_NativeTyping_rankSuggestions(
        mut env: JNIEnv<'_>,
        _: JObject<'_>,
        mode: jint,
        prefix: JString<'_>,
        context: JString<'_>,
        before: JString<'_>,
    ) -> jstring {
        let mut compute = || -> jni::errors::Result<jstring> {
            let prefix: String = env.get_string(&prefix)?.into();
            let context: String = env.get_string(&context)?.into();
            let before: String = env.get_string(&before)?.into();
            let result = lexicon::suggest(mode as usize, &prefix, &context, &before);
            result.map_or(Ok(null_mut()), |text| Ok(env.new_string(text)?.into_raw()))
        };
        compute().unwrap_or(null_mut())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pcm_uses_signed_little_endian_samples() {
        let audio = [0, 128, 0, 64, 255];
        assert_eq!(pcm_samples(&audio).collect::<Vec<_>>(), [-1.0, 0.5]);
        assert!((pcm_rms(&audio) - 0.79057).abs() < 0.0001);
        assert_eq!(pcm_rms(&[]), 0.0);
    }

    #[test]
    fn context_breaks_a_swipe_tie_and_keeps_alternatives() {
        let mut centers = [Point(0.0, 0.0); 27];
        centers[2] = Point(1.0, 1.0);
        centers[17] = Point(1.0, 0.0);
        let trace = [centers[0], centers[17], centers[2]];
        assert!(lexicon::install(2, "arcc\t55\narc\t55", "", ""));
        assert_eq!(
            lexicon::swipe(2, &trace, &centers, "arc", "").as_deref(),
            Some("arc\narcc")
        );
        assert_eq!(
            lexicon::swipe(2, &trace[..1], &centers, "", "").as_deref(),
            Some("")
        );
    }

    #[test]
    fn suggestions_use_context_and_exclude_invalid_candidates() {
        assert!(lexicon::install(3, "cat\t55\ncar\t55\nCAP\t100", "", ""));
        assert_eq!(
            lexicon::suggest(3, "ca", "car", "").as_deref(),
            Some("\ncar\ncat")
        );
    }

    #[test]
    fn direct_two_letter_sweeps_do_not_pick_up_intermediate_keys() {
        let mut centers = [Point(1000.0, 1000.0); 27];
        for (row, keys) in ["qwertyuiop", "asdfghjkl"].iter().enumerate() {
            for (column, key) in keys.bytes().enumerate() {
                centers[(key - b'a') as usize] =
                    Point(column as f32 + 0.5 + row as f32 * 0.5, (row as f32 + 0.5) * 1.25);
            }
        }
        let key = |letter: u8| centers[(letter - b'a') as usize];
        assert!(lexicon::install(
            0,
            include_str!("../../assets/english-4.0.0.dic"),
            "is it its in at",
            ""
        ));
        let direct = [key(b'i'), Point(5.4, 1.17), Point(3.3, 1.56), key(b's')];
        let result = lexicon::swipe(0, &direct, &centers, "", "this ").unwrap();
        assert_eq!(result.lines().next(), Some("is"));
        assert!(result.lines().count() > 1);
        let noisy = [Point(7.35, 0.78), Point(4.6, 1.15), Point(2.16, 1.79)];
        let result = lexicon::swipe(0, &noisy, &centers, "", "").unwrap();
        assert_eq!(result.lines().next(), Some("is"));
        let deliberate = [key(b'i'), key(b't'), key(b's')];
        let result = lexicon::swipe(0, &deliberate, &centers, "", "").unwrap();
        assert_eq!(result.lines().next(), Some("its"));
    }
}
