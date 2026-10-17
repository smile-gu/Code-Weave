# Pinned DeepSeek V4.1 reference assets

Model repository: `deepseek-ai/DeepSeek-V4.1-Flash`, revision
`2cba9e42aa026125f3ed06c6d98c1db82f7ca027`.
Acquired through `hf-mirror.com`; independent verification against the main
Hugging Face host remains outstanding. No model weights are bundled.

The original reference encoder, tokenizer, tokenizer configuration and LICENSE
are unchanged. Runtime checks all four SHA-256 values before loading code or
tokenizer. Only text, non-thinking Chat Completions is exposed by the platform.
No image dependencies, network downloads or runtime asset updates are used.

Meter basis: `V41_REFERENCE_LOCAL_TOKENS`, version `v4.1-2cba9e42-v1`.
On 2026-10-10 six finite official hosted samples matched input usage: plain 17,
Chinese/code 35, JSON 47, summary 34, auto tools 280, auto closed history 346.
These samples do not establish global parity. The two `none` tool samples
overestimated by 263; no subtraction or proportional correction is applied.
Source evidence is recorded in external design documents 22/24; no credential
or paid request is included in this repository.
