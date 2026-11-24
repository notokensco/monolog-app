use std::collections::HashMap;
use std::sync::OnceLock;

type Counts = HashMap<&'static str, u16>;

#[derive(Default)]
struct Model {
    unigrams: Counts,
    bigrams: HashMap<&'static str, Counts>,
    trigrams: HashMap<(&'static str, &'static str), Counts>,
}

impl Model {
    fn ingest(&mut self, phrases: &'static str, weight: u16) {
        for line in phrases.lines() {
            let words: Vec<_> = line.split_whitespace().collect();
            for (i, &word) in words.iter().enumerate() {
                *self.unigrams.entry(word).or_default() += weight;
                if i >= 1 {
                    *self
                        .bigrams
                        .entry(words[i - 1])
                        .or_default()
                        .entry(word)
                        .or_default() += weight;
                }
                if i >= 2 {
                    *self
                        .trigrams
                        .entry((words[i - 2], words[i - 1]))
                        .or_default()
                        .entry(word)
                        .or_default() += weight;
                }
            }
        }
    }
}

struct Context<'a> {
    model: &'a Model,
    bigrams: Option<&'a Counts>,
    trigrams: Option<&'a Counts>,
}

impl Context<'_> {
    fn score(&self, word: &str, unigram: bool) -> f32 {
        let count = |counts: Option<&Counts>| {
            counts
                .and_then(|entry| entry.get(word))
                .copied()
                .unwrap_or(0) as f32
        };
        let prior = if unigram {
            count(Some(&self.model.unigrams)).ln_1p() * 0.012
        } else {
            0.0
        };
        prior + count(self.bigrams).ln_1p() * 0.048 + count(self.trigrams).ln_1p() * 0.085
    }
}

fn context(mode: usize, text: &str) -> Option<Context<'_>> {
    static MODELS: [OnceLock<Model>; 2] = [const { OnceLock::new() }; 2];
    let model = MODELS.get(mode)?.get_or_init(|| {
        let mut model = Model::default();
        model.ingest(
            include_str!("english_phrases.txt"),
            if mode == 0 { 2 } else { 1 },
        );
        model.ingest(include_str!("hinglish_phrases.txt"), if mode == 0 { 1 } else { 3 });
        model
    });
    let sentence = text.rsplit(['.', '?', '!', '\n']).next().unwrap_or("");
    let mut words = sentence
        .rsplit(|c: char| !c.is_alphabetic() && c != '\'')
        .filter(|word| !word.is_empty());
    let last = words.next();
    Some(Context {
        model,
        bigrams: last.and_then(|word| model.bigrams.get(word)),
        trigrams: words
            .next()
            .zip(last)
            .and_then(|pair| model.trigrams.get(&pair)),
    })
}

pub(crate) fn ranker(mode: usize, text: &str) -> impl Fn(&str) -> f32 + '_ {
    let context = context(mode, text);
    move |word| context.as_ref().map_or(0.0, |ctx| ctx.score(word, true))
}

pub(crate) fn next(mode: usize, text: &str) -> String {
    let Some(ctx) = context(mode, text) else {
        return String::new();
    };
    let mut candidates: Vec<_> = ctx
        .bigrams
        .into_iter()
        .chain(ctx.trigrams)
        .flat_map(|counts| counts.keys().copied())
        .collect();
    candidates.sort_unstable_by(|&a, &b| {
        ctx.score(b, false)
            .total_cmp(&ctx.score(a, false))
            .then_with(|| a.cmp(b))
    });
    candidates.dedup();
    candidates.truncate(3);
    candidates.join("\n")
}

pub(crate) fn rank_personal_transitions(records: &str) -> String {
    let mut by_predecessor = std::collections::BTreeMap::<_, Vec<_>>::new();
    for line in records.lines().take(256) {
        let mut fields = line.split('\t');
        let (Some(previous), Some(next), Some(uses), Some(last_used)) =
            (fields.next(), fields.next(), fields.next(), fields.next())
        else {
            continue;
        };
        let (Ok(uses), Ok(last_used)) = (uses.parse::<u8>(), last_used.parse::<u64>()) else {
            continue;
        };
        if uses > 0 && !previous.is_empty() && !next.is_empty() {
            by_predecessor.entry(previous).or_default().push((next, uses, last_used));
        }
    }
    let mut ranked = String::new();
    for (previous, mut candidates) in by_predecessor {
        candidates.sort_unstable_by(|(a, a_uses, a_time), (b, b_uses, b_time)| {
            b_uses.cmp(a_uses).then_with(|| b_time.cmp(a_time)).then_with(|| a.cmp(b))
        });
        ranked.push_str(previous);
        for (next, _, _) in candidates.into_iter().take(4) {
            ranked.push('\t');
            ranked.push_str(next);
        }
        ranked.push('\n');
    }
    ranked
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn personal_transitions_prioritize_frequency_then_recency() {
        let actual = rank_personal_transitions(
            "call\tsam\t1\t40\ncall\tsamia\t3\t25\ncall\tsamira\t3\t30\nbook\tcab\t1\t50",
        );
        assert_eq!(actual, "book\tcab\ncall\tsamira\tsamia\tsam\n");
    }

    #[test]
    fn ranks_english_and_code_switched_phrases() {
        assert_eq!(next(0, "send the file").lines().next(), Some("before"));
        assert_eq!(next(1, "meeting ka").lines().next(), Some("link"));
        assert_eq!(next(1, "aaj ki meeting ka?"), "");
        assert!(ranker(1, "please location")("share") > ranker(1, "please location")("search"));
    }
}
