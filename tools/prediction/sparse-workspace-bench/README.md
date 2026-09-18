# Sparse workspace JNI benchmark

This is the existing display/exact JNI comparison harness with additional
8x8 batches at 128, 256 and 512 block spacing. Sparse scenarios use 16 batches
(1,024 requested columns) per fresh world. Dense and warm-cache controls retain
their previous workloads. The companion native declarations are standalone
benchmark stubs, not replacements for the production Java classes.

Compile all Java files in this directory into a separate classes directory with
JDK 25. Run `tools/prediction/compare-display-libraries.py` with `--before`,
`--after`, `--classes`, `--java`, repeated `--document name=path`, `--output`,
`--seeds`, and `--rounds`. Add
`--scenes batch8_step64 batch8_step128 batch8_step256 batch8_step512` for the
sparse suite. Omitting `--scenes` retains the existing dense/sparse controls.

Each process loads exactly one native library. The runner alternates old/new
order between repetitions, records file hashes, checks the complete 40-byte
output checksum and stores height/water/material TSVs. Display runs also emit
native fallback counts before/after the measured scenario. The comparison
rejects any output or fallback-count difference. Timings are microseconds per
requested column; they are not game FPS or end-to-end loading times.
