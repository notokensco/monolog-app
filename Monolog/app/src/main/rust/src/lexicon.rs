use crate::{sample, Point, MAX_WORD_LENGTH, SAMPLE_COUNT};
use std::sync::OnceLock;

const KEYS: usize = 27;
static DICTIONARIES: [OnceLock<Dictionary>; 5] = [const { OnceLock::new() }; 5];

struct Word {
    text: Box<str>,
    weight: f32,
    swipe: bool,
}

struct Dictionary {
    words: Vec<Word>,
    endpoints: [Vec<usize>; KEYS * KEYS],
}

fn key(letter: char) -> Option<usize> {
    let base = match letter {
        'à' | 'á' | 'â' | 'ä' | 'ã' | 'å' | 'æ' => 'a',
        'ç' => 'c',
        'è' | 'é' | 'ê' | 'ë' => 'e',
        'ì' | 'í' | 'î' | 'ï' => 'i',
        'ò' | 'ó' | 'ô' | 'ö' | 'õ' | 'œ' => 'o',
        'ù' | 'ú' | 'û' | 'ü' => 'u',
        'ý' | 'ÿ' => 'y',
        'ß' => 's',
        'ñ' => return Some(26),
        _ => letter,
    };
    base.is_ascii_lowercase()
        .then(|| (base as u8 - b'a') as usize)
}

fn key_indices(word: &str) -> impl DoubleEndedIterator<Item = usize> + '_ {
    word.chars().filter_map(key)
}

fn add(words: &mut Vec<Word>, text: &str, weight: f32, swipe: bool) {
    if text.chars().count() <= MAX_WORD_LENGTH
        && !text.is_empty()
        && text.chars().all(|c| key(c).is_some() || c == '\'')
        && key_indices(text).next().is_some()
    {
        words.push(Word {
            text: text.into(),
            weight,
            swipe,
        });
    }
}

fn add_english(words: &mut Vec<Word>, line: &str) {
    let line = line.split('\t').next().unwrap_or("");
    let (word, flags) = line.split_once('/').unwrap_or((line, ""));
    if word.len() < 2 || !word.bytes().all(|c| c.is_ascii_lowercase() || c == b'\'') {
        return;
    }
    let swipe = !flags.contains('!');
    add(words, word, 0.0, swipe);
    if !swipe || word.contains('\'') {
        return;
    }
    let consonant_y = word.ends_with('y') && !b"aeiou".contains(&word.as_bytes()[word.len() - 2]);
    if flags.contains('G') {
        add(
            words,
            &format!("{}ing", word.strip_suffix('e').unwrap_or(word)),
            0.0,
            true,
        );
    }
    if flags.contains('D') {
        let variant = if word.ends_with('e') {
            format!("{word}d")
        } else if consonant_y {
            format!("{}ied", &word[..word.len() - 1])
        } else {
            format!("{word}ed")
        };
        add(words, &variant, 0.0, true);
    }
    if flags.contains('S') {
        let variant = if consonant_y {
            format!("{}ies", &word[..word.len() - 1])
        } else if word.ends_with(['s', 'x', 'z', 'h']) {
            format!("{word}es")
        } else {
            format!("{word}s")
        };
        add(words, &variant, 0.0, true);
    }
    if flags.contains('Y') {
        add(words, &format!("{word}ly"), 0.0, true);
    }
}

pub(crate) fn install(mode: usize, corpus: &str, preferred: &str, secondary: &str) -> bool {
    let Some(slot) = DICTIONARIES.get(mode) else {
        return false;
    };
    let lexicon = slot.get_or_init(|| {
        let mut words = Vec::new();
        if mode <= 1 {
            corpus
                .lines()
                .skip(1)
                .for_each(|line| add_english(&mut words, line));
        } else {
            for line in corpus.lines() {
                let (word, weight) = line.split_once('\t').unwrap_or((line, "55"));
                let weight = weight.parse::<u8>().unwrap_or(55) as f32 / 1000.0;
                add(&mut words, word, weight, true);
            }
        }
        if mode <= 1 {
            for word in include_str!("hinglish_phrases.txt").split_whitespace() {
                add(&mut words, word, if mode == 1 { 0.09 } else { 0.035 }, true);
            }
        }
        for word in preferred.split_whitespace() {
            add(&mut words, word, 0.12, true);
        }
        for word in secondary.split_whitespace() {
            add(&mut words, word, 0.07, true);
        }
        words.sort_unstable_by(|a, b| {
            a.text
                .cmp(&b.text)
                .then_with(|| b.weight.total_cmp(&a.weight))
        });
        words.dedup_by(|a, b| {
            if a.text != b.text {
                return false;
            }
            b.swipe |= a.swipe;
            true
        });
        let mut endpoints: [Vec<usize>; KEYS * KEYS] = std::array::from_fn(|_| Vec::new());
        for (id, word) in words.iter().enumerate().filter(|(_, word)| word.swipe) {
            let Some(first) = key_indices(&word.text).next() else {
                continue;
            };
            let Some(last) = key_indices(&word.text).next_back() else {
                continue;
            };
            endpoints[first * KEYS + last].push(id);
        }
        Dictionary { words, endpoints }
    });
    !lexicon.words.is_empty()
}

#[derive(Default)]
struct Best<'a>(Vec<(f32, &'a str)>);

impl<'a> Best<'a> {
    fn push(&mut self, score: f32, word: &'a str) {
        if self.0.iter().any(|item| item.1 == word) {
            return;
        }
        let index = self.0.partition_point(|item| item.0 <= score);
        if index < 3 {
            self.0.insert(index, (score, word));
            self.0.truncate(3);
        }
    }

    fn items(self) -> Vec<&'a str> {
        self.0.into_iter().map(|item| item.1).collect()
    }
}

fn context_weight(word: &str, next: &[&str]) -> f32 {
    next.iter()
        .position(|value| *value == word)
        .map_or(0.0, |rank| 0.14 - 0.025 * rank as f32)
}

fn get(mode: usize) -> Option<&'static Dictionary> {
    DICTIONARIES
        .get(mode)?
        .get()
        .filter(|d| !d.words.is_empty())
}

impl Dictionary {
    fn exact(&self, word: &str) -> Option<&Word> {
        self.words
            .binary_search_by(|value| value.text.as_ref().cmp(word))
            .ok()
            .map(|id| &self.words[id])
    }

    fn edits<'a>(&'a self, word: &str, mut accept: impl FnMut(&'a Word, f32)) {
        let mut buffer = [0; MAX_WORD_LENGTH + 1];
        let bytes = word.as_bytes();
        let len = bytes.len();
        let mut check = |trial: &[u8], penalty: f32| {
            if let Some(candidate) = std::str::from_utf8(trial)
                .ok()
                .and_then(|trial| self.exact(trial))
            {
                if candidate.text.as_ref() != word {
                    accept(candidate, penalty);
                }
            }
        };
        buffer[..len].copy_from_slice(bytes);
        for i in 0..len - 1 {
            buffer.swap(i, i + 1);
            check(&buffer[..len], 0.0);
            buffer.swap(i, i + 1);
        }
        for i in 0..=len {
            buffer[..i].copy_from_slice(&bytes[..i]);
            if i < len {
                buffer[i..len - 1].copy_from_slice(&bytes[i + 1..]);
                check(&buffer[..len - 1], 0.025);
                buffer[i + 1..len].copy_from_slice(&bytes[i + 1..]);
                for letter in b'a'..=b'z' {
                    buffer[i] = letter;
                    let penalty = if adjacent(bytes[i], letter) { 0.015 } else { 0.095 };
                    check(&buffer[..len], penalty);
                }
            }
            buffer[i + 1..len + 1].copy_from_slice(&bytes[i..]);
            for letter in b'a'..=b'z' {
                buffer[i] = letter;
                let repeated = i.checked_sub(1).and_then(|index| bytes.get(index)) == Some(&letter)
                    || bytes.get(i) == Some(&letter);
                check(&buffer[..=len], if repeated { 0.018 } else { 0.045 });
            }
        }
    }
}

fn adjacent(a: u8, b: u8) -> bool {
    fn position(key: u8) -> Option<(i32, i32)> {
        [b"qwertyuiop".as_slice(), b"asdfghjkl", b"zxcvbnm"]
            .iter()
            .enumerate()
            .find_map(|(row, keys)| {
                keys.iter().position(|&letter| letter == key)
                    .map(|column| (column as i32 * 2 + row as i32, row as i32))
            })
    }
    position(a).zip(position(b)).is_some_and(|((x1, y1), (x2, y2))| {
        y1.abs_diff(y2) <= 1 && x1.abs_diff(x2) <= if y1 == y2 { 2 } else { 1 }
    })
}

pub(crate) fn suggest(mode: usize, prefix: &str, context: &str, before: &str) -> Option<String> {
    let dict = get(mode)?;
    let next: Vec<_> = context.split_whitespace().take(3).collect();
    let weight = crate::phrases::ranker(mode, before);
    let mut best = Best::default();
    let mut unique = None;
    let mut ambiguous = false;
    if mode == 0
        && (3..=MAX_WORD_LENGTH).contains(&prefix.len())
        && prefix.bytes().all(|c| c.is_ascii_lowercase() || c == b'\'')
        && dict.exact(prefix).is_none()
    {
        dict.edits(prefix, |entry, penalty| {
            let word = entry.text.as_ref();
            if unique.is_some_and(|first| first != word) {
                ambiguous = true;
            }
            unique = Some(word);
            best.push(
                0.12 + penalty - entry.weight - context_weight(word, &next).max(weight(word)),
                word,
            );
        });
    }
    let start = dict
        .words
        .partition_point(|entry| entry.text.as_ref() < prefix);
    for entry in dict.words[start..]
        .iter()
        .take_while(|entry| entry.text.starts_with(prefix))
    {
        let word = entry.text.as_ref();
        if word == prefix {
            continue;
        }
        let length_cost = (word.chars().count() - prefix.chars().count()) as f32 * 0.016;
        best.push(
            0.19 + length_cost - entry.weight - context_weight(word, &next).max(weight(word)),
            word,
        );
    }
    Some(format!(
        "{}\n{}",
        if ambiguous { "" } else { unique.unwrap_or("") },
        best.items().join("\n")
    ))
}

pub(crate) fn swipe(
    mode: usize,
    trace: &[Point],
    centers: &[Point],
    context: &str,
    before: &str,
) -> Option<String> {
    let dict = get(mode)?;
    let Some((observed, trace_length)) = sample(trace) else {
        return Some(String::new());
    };
    if centers.len() != KEYS {
        return None;
    }
    let next: Vec<_> = context.split_whitespace().take(3).collect();
    let first = observed[0];
    let last = observed[SAMPLE_COUNT - 1];
    let weight = crate::phrases::ranker(mode, before);
    let mut best = Best::default();
    for (from, center) in centers.iter().enumerate() {
        let start_error = first.squared_distance(*center);
        if start_error > 2.0 {
            continue;
        }
        for (to, center) in centers.iter().enumerate() {
            let end_error = last.squared_distance(*center);
            if end_error > 2.0 {
                continue;
            }
            for id in &dict.endpoints[from * KEYS + to] {
                let word = &dict.words[*id];
                let mut keys = [Point::default(); MAX_WORD_LENGTH];
                let mut count = 0;
                for idx in key_indices(&word.text) {
                    keys[count] = centers[idx];
                    count += 1;
                }
                let Some((expected, length)) = sample(&keys[..count]) else {
                    continue;
                };
                let length_error = (length - trace_length).abs() / (length + trace_length);
                if length_error > 0.48 {
                    continue;
                }
                let shape_error = observed
                    .iter()
                    .enumerate()
                    .map(|(i, actual)| {
                        // Nearby samples tolerate a turn drawn slightly earlier or later.
                        let from = i.saturating_sub(1);
                        let to = (i + 1).min(SAMPLE_COUNT - 1);
                        (from..=to)
                            .map(|j| {
                                actual.squared_distance(expected[j]) + 0.025 * i.abs_diff(j) as f32
                            })
                            .fold(f32::INFINITY, f32::min)
                    })
                    .sum::<f32>()
                    / SAMPLE_COUNT as f32;
                let score = shape_error
                    + 0.12 * keys[..count].iter().map(|key| {
                        observed.iter().map(|point| key.squared_distance(*point))
                            .fold(f32::INFINITY, f32::min).min(1.5)
                    }).sum::<f32>() / count as f32
                    + 0.48 * (start_error + end_error)
                    + 0.40 * length_error
                    + 0.005 * count as f32
                    - (word.weight + context_weight(&word.text, &next).max(weight(&word.text)))
                        .min(0.34);
                if score < 1.05 {
                    best.push(score, &word.text);
                }
            }
        }
    }
    Some(best.items().join("\n"))
}
