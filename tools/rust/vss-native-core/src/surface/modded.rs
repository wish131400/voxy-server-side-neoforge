//! Exact mod surface rules. Resolve references and random choices once per world.
use super::*;
use crate::providers::IntProvider;

fn weighted<T>(v: &Value, mut parse: impl FnMut(&Value) -> Result<T>) -> Result<(Vec<T>, Vec<i32>, i32)> {
    let entries = v.as_array().ok_or("missing surface weighted entries")?;
    let mut values = Vec::new();
    let mut weights = Vec::new();
    let mut total = 0i32;
    for entry in entries {
        let weight = integer(entry, "weight")?;
        if weight < 0 { return Err("negative surface weight".into()); }
        total = total.checked_add(weight).ok_or("surface weight overflow")?;
        values.push(parse(&entry["data"])?);
        weights.push(weight);
    }
    if total == 0 { return Err("empty surface weighted entries".into()); }
    Ok((values, weights, total))
}

fn choose(weights: &[i32], total: i32, random: &mut Random) -> usize {
    let mut value = random.next_bounded(total);
    for (index, weight) in weights.iter().enumerate() {
        value -= weight;
        if value < 0 { return index; }
    }
    unreachable!()
}

pub(super) fn parse(v: &Value, g: &mut Graph, doc: &Value, p: &mut Palette,
    min: i32, height: i32, depth: usize) -> Result<Option<Rule>> {
    Ok(Some(match string(v, "type")? {
        "lithostitched:reference" => {
            let mut rules = Vec::new();
            reference(&v["rules"], &mut rules, g, doc, p, min, height, depth + 1)?;
            // An empty holder set returns no result; the enclosing sequence/base continues.
            Rule::Sequence(rules)
        }
        "biomeswevegone:state_provider" => {
            let (rules, weights, total) = weighted(&v["provider"], |r| parse_rule(r, g, doc, p, min, height, depth + 1))?;
            let mut columns = Box::new([0usize; 256]);
            for x in 0..16 { for z in 0..16 {
                columns[x * 16 + z] = choose(&weights, total, &mut g.random.at(x as i32, 0, z as i32));
            }}
            Rule::Weighted { rules, columns }
        }
        "biomeswevegone:between_repeating_noise_range" => {
            let size = number(v, "size_per_repetition")? as f32;
            let mut start = number(v, "repeat_from_noise")? as f32;
            let end = number(v, "repeat_to_noise")? as f32;
            if !size.is_finite() || size <= 0. || !start.is_finite() || !end.is_finite() {
                return Err("invalid repeating surface noise interval".into());
            }
            let sources = v["rule_sources"].as_array().ok_or("missing repeating surface rules")?;
            if sources.is_empty() { return Err("empty repeating surface rules".into()); }
            let noise = g.registered_noise(string(v, "noise")?, doc)?;
            let mut rules = Vec::new();
            while start <= end {
                if rules.len() >= 4096 { return Err("surface sequence budget".into()); }
                let upper = start + size;
                rules.push(Rule::Condition(Condition::Noise(noise, start as f64, upper as f64),
                    Box::new(parse_rule(&sources[rules.len() % sources.len()], g, doc, p, min, height, depth + 1)?)));
                let next = start + size * 2.;
                if next <= start { return Err("non-progressing surface noise interval".into()); }
                start = next;
            }
            Rule::Sequence(rules)
        }
        "biomeswevegone:bands" => {
            let (states, weights, total) = weighted(&v["band_states"], |state| p.intern(state))?;
            let size = IntProvider::parse(&v["band_size"])?;
            let count = IntProvider::parse(&v["bands_count"])?;
            let mut random = g.random.at(0, 0, 0);
            let count = count.sample(&mut random);
            if count <= 0 || count > 1_048_576 { return Err("invalid surface band count".into()); }
            let mut bands = Vec::new();
            for _ in 0..count {
                let size = size.sample(&mut random);
                let state = states[choose(&weights, total, &mut random)];
                if size <= 0 || bands.len() as i64 + size as i64 > 1_048_576 {
                    return Err("invalid surface band size".into());
                }
                bands.resize(bands.len() + size as usize, state);
            }
            // BWG uses length-1 as its modulus. Preserve the released algorithm.
            if bands.len() < 2 { return Err("surface bands need at least two states".into()); }
            Rule::ModBands { states: bands, frequency: number(v, "frequency")? as f32,
                noise_scale: integer(v, "noise_scale")?,
                noise: g.registered_noise("minecraft:surface_secondary", doc)? }
        }
        _ => return Ok(None),
    }))
}

fn reference(v: &Value, rules: &mut Vec<Rule>, g: &mut Graph, doc: &Value,
    p: &mut Palette, min: i32, height: i32, depth: usize) -> Result<()> {
    if depth > 128 { return Err("surface rule depth exceeded".into()); }
    if let Some(values) = v.as_array() {
        for value in values { reference(value, rules, g, doc, p, min, height, depth + 1)?; }
    } else if let Some(id) = v.as_str() {
        if let Some(tag) = id.strip_prefix('#') {
            let tag = if tag.contains(':') { tag.to_owned() } else { format!("minecraft:{tag}") };
            let members = &doc["registry_tags"]["lithostitched:surface_rule"][&tag];
            if !members.is_array() { return Err(format!("missing surface rule tag {tag}")); }
            reference(members, rules, g, doc, p, min, height, depth + 1)?;
        } else {
            let id = if id.contains(':') { id.to_owned() } else { format!("minecraft:{id}") };
            let value = &doc["custom_registries"]["lithostitched:surface_rule"][&id];
            if value.is_null() { return Err(format!("missing surface rule reference {id}")); }
            rules.push(parse_rule(value, g, doc, p, min, height, depth + 1)?);
        }
    } else if v.is_object() {
        rules.push(parse_rule(v, g, doc, p, min, height, depth + 1)?);
    } else { return Err("invalid surface rule reference set".into()); }
    Ok(())
}
