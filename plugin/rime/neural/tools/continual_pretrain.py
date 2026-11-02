#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""
Continual pretraining / domain adaptation and INT8 safetensors export tool
for the chinese-ime-lm 4M character-level GPT model.

Supports two backends:
  1. PyTorch (`--backend torch`, CUDA / CPU) for full-corpus continual pretraining.
  2. NumPy (`--backend numpy`, zero extra dependencies) for immediate local
     phrase/dictionary continual adaptation and model inspection.

The exported `.safetensors` file preserves the exact tensor layout, per-row
symmetric INT8 quantization (`weight` + `weight.scale`), and header metadata
(`config`, `vocab`, `attribution`, `license`) expected by `chinese-ime-lm`.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import struct
import sys
import time
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

import numpy as np

UNK_TOKEN = 1
BOS_TOKEN = 2


@dataclass
class ModelConfig:
    vocab: int
    n_layer: int
    n_head: int
    n_embd: int
    context: int


@dataclass
class SafetensorsBundle:
    config: ModelConfig
    vocab: List[str]
    char_to_id: Dict[str, int]
    metadata: Dict[str, str]
    tensors: Dict[str, np.ndarray]


def _f16_to_f32(raw_bytes: bytes, shape: Sequence[int]) -> np.ndarray:
    return np.frombuffer(raw_bytes, dtype=np.float16).astype(np.float32).reshape(shape)


def load_safetensors(path: pathlib.Path) -> SafetensorsBundle:
    """Load and dequantize a chinese-ime-lm `.safetensors` file into FP32 arrays."""
    raw = path.read_bytes()
    if len(raw) < 8:
        raise ValueError(f"{path}: truncated safetensors header")
    (header_len,) = struct.unpack("<Q", raw[:8])
    header_end = 8 + header_len
    if header_end > len(raw):
        raise ValueError(f"{path}: header extends past end of file")

    header = json.loads(raw[8:header_end].decode("utf-8"))
    data = memoryview(raw)[header_end:]

    metadata: Dict[str, str] = header.pop("__metadata__", {})
    if "config" not in metadata or "vocab" not in metadata:
        raise ValueError(f"{path}: missing required 'config' or 'vocab' metadata")

    cfg_dict = json.loads(metadata["config"])
    config = ModelConfig(
        vocab=int(cfg_dict["vocab"]),
        n_layer=int(cfg_dict["n_layer"]),
        n_head=int(cfg_dict["n_head"]),
        n_embd=int(cfg_dict["n_embd"]),
        context=int(cfg_dict["context"]),
    )
    vocab: List[str] = json.loads(metadata["vocab"])
    char_to_id: Dict[str, int] = {}
    for idx, token in enumerate(vocab):
        if len(token) == 1:
            char_to_id[token] = idx

    tensors: Dict[str, np.ndarray] = {}
    for name, entry in header.items():
        if name.endswith(".scale"):
            continue
        dtype = entry["dtype"]
        shape = tuple(int(d) for d in entry["shape"])
        start, end = int(entry["data_offsets"][0]), int(entry["data_offsets"][1])
        chunk = data[start:end]
        if dtype == "F32":
            arr = np.frombuffer(chunk, dtype="<f4").astype(np.float32, copy=True).reshape(shape)
        elif dtype == "F16":
            arr = _f16_to_f32(chunk, shape)
        elif dtype == "I8":
            scale_entry = header.get(f"{name}.scale")
            if scale_entry is None:
                raise ValueError(f"{path}: quantized tensor {name} missing {name}.scale")
            s_start, s_end = int(scale_entry["data_offsets"][0]), int(scale_entry["data_offsets"][1])
            scales = np.frombuffer(data[s_start:s_end], dtype="<f4").astype(np.float32, copy=True)
            q_vals = np.frombuffer(chunk, dtype=np.int8).reshape(shape)
            if len(shape) != 2 or scales.shape[0] != shape[0]:
                raise ValueError(f"{path}: invalid scale shape {scales.shape} for {name} {shape}")
            arr = q_vals.astype(np.float32) * scales[:, None]
        else:
            raise ValueError(f"{path}: unsupported dtype {dtype} for {name}")
        tensors[name] = arr

    return SafetensorsBundle(
        config=config,
        vocab=vocab,
        char_to_id=char_to_id,
        metadata=metadata,
        tensors=tensors,
    )


def _should_quantize(name: str, arr: np.ndarray) -> bool:
    if arr.ndim != 2:
        return False
    if name == "pos.weight":
        return False
    return name == "tok.weight" or name.endswith((".qkv.weight", ".proj.weight", ".fc.weight", ".out.weight"))


def save_safetensors(
    path: pathlib.Path,
    bundle: SafetensorsBundle,
    tensors: Dict[str, np.ndarray],
    quantize_int8: bool = True,
) -> None:
    """Export model weights to a chinese-ime-lm compatible `.safetensors` file."""
    header: Dict[str, object] = {"__metadata__": dict(bundle.metadata)}
    payloads: List[bytes] = []
    offset = 0

    def add_raw_entry(name: str, dtype_str: str, shape: Sequence[int], raw_bytes: bytes) -> None:
        nonlocal offset
        next_offset = offset + len(raw_bytes)
        header[name] = {
            "dtype": dtype_str,
            "shape": list(shape),
            "data_offsets": [offset, next_offset],
        }
        payloads.append(raw_bytes)
        offset = next_offset

    for name in sorted(tensors.keys()):
        arr = np.asarray(tensors[name], dtype=np.float32)
        if quantize_int8 and _should_quantize(name, arr):
            max_abs = np.max(np.abs(arr), axis=1)
            scales = np.where(max_abs > 0.0, max_abs / 127.0, 1.0).astype("<f4")
            quantized = np.clip(np.rint(arr / scales[:, None]), -127.0, 127.0).astype(np.int8)
            add_raw_entry(name, "I8", quantized.shape, quantized.tobytes(order="C"))
            add_raw_entry(f"{name}.scale", "F32", scales.shape, scales.tobytes(order="C"))
        else:
            f32_bytes = arr.astype("<f4", copy=False).tobytes(order="C")
            add_raw_entry(name, "F32", arr.shape, f32_bytes)

    header_bytes = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    pad_len = (8 - (len(header_bytes) % 8)) % 8
    if pad_len:
        header_bytes += b" " * pad_len

    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as f:
        f.write(struct.pack("<Q", len(header_bytes)))
        f.write(header_bytes)
        for chunk in payloads:
            f.write(chunk)


def encode_text(text: str, char_to_id: Dict[str, int], limit: Optional[int] = None) -> List[int]:
    chars = list(text) if limit is None else list(text)[:limit]
    return [char_to_id.get(ch, UNK_TOKEN) for ch in chars]


def prefix_tokens(context: str, longest_cand: int, config: ModelConfig, char_to_id: Dict[str, int]) -> List[int]:
    room = max(0, config.context - (longest_cand + 1))
    encoded = encode_text(context, char_to_id)
    start = max(0, len(encoded) - room)
    return [BOS_TOKEN] + encoded[start:]


# ---------------------------------------------------------------------------
# Reference NumPy Forward & Analytical Backprop Engine
# ---------------------------------------------------------------------------

def _gelu(x: np.ndarray) -> np.ndarray:
    # Abramowitz & Stegun 7.1.26 matching vendor/chinese-ime-lm/src/lib.rs
    z = x * np.float32(1.0 / math.sqrt(2.0))
    sign = np.where(z < 0.0, np.float32(-1.0), np.float32(1.0))
    ax = np.abs(z)
    t = np.float32(1.0) / (np.float32(1.0) + np.float32(0.3275911) * ax)
    poly = t * (
        np.float32(0.2548296)
        + t
        * (
            np.float32(-0.28449674)
            + t * (np.float32(1.4214137) + t * (np.float32(-1.453152) + t * np.float32(1.0614054)))
        )
    )
    erf_val = sign * (np.float32(1.0) - poly * np.exp(-ax * ax))
    return np.float32(0.5) * x * (np.float32(1.0) + erf_val)


def _gelu_grad(x: np.ndarray, grad_out: np.ndarray) -> np.ndarray:
    z = x * np.float32(1.0 / math.sqrt(2.0))
    sign = np.where(z < 0.0, np.float32(-1.0), np.float32(1.0))
    ax = np.abs(z)
    t = np.float32(1.0) / (np.float32(1.0) + np.float32(0.3275911) * ax)
    poly = t * (
        np.float32(0.2548296)
        + t
        * (
            np.float32(-0.28449674)
            + t * (np.float32(1.4214137) + t * (np.float32(-1.453152) + t * np.float32(1.0614054)))
        )
    )
    erf_val = sign * (np.float32(1.0) - poly * np.exp(-ax * ax))
    pdf = np.float32(1.0 / math.sqrt(2.0 * math.pi)) * np.exp(np.float32(-0.5) * x * x)
    dgelu = np.float32(0.5) * (np.float32(1.0) + erf_val) + x * pdf
    return grad_out * dgelu


def _layer_norm_fwd(x: np.ndarray, weight: np.ndarray, bias: np.ndarray) -> Tuple[np.ndarray, Tuple[np.ndarray, np.ndarray, np.ndarray]]:
    mean = np.mean(x, axis=-1, keepdims=True)
    diff = x - mean
    var = np.mean(diff * diff, axis=-1, keepdims=True)
    inv_std = np.float32(1.0) / np.sqrt(var + np.float32(1e-5))
    x_hat = diff * inv_std
    out = x_hat * weight + bias
    return out, (x_hat, inv_std, weight)


def _layer_norm_bwd(
    grad_out: np.ndarray,
    cache: Tuple[np.ndarray, np.ndarray, np.ndarray],
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    x_hat, inv_std, weight = cache
    axes = tuple(range(grad_out.ndim - 1))
    grad_w = np.sum(grad_out * x_hat, axis=axes)
    grad_b = np.sum(grad_out, axis=axes)
    dx_hat = grad_out * weight
    n = np.float32(grad_out.shape[-1])
    grad_x = (
        (n * dx_hat - np.sum(dx_hat, axis=-1, keepdims=True) - x_hat * np.sum(dx_hat * x_hat, axis=-1, keepdims=True))
        * inv_std
        / n
    )
    return grad_x, grad_w, grad_b


def numpy_forward_logits(
    token_ids: np.ndarray,
    config: ModelConfig,
    tensors: Dict[str, np.ndarray],
) -> np.ndarray:
    """
    Forward pass for token_ids of shape (B, T) -> logits of shape (B, T, V).
    Matches `vendor/chinese-ime-lm/src/lib.rs`.
    """
    bsz, seq_len = token_ids.shape
    n_embd = config.n_embd
    n_head = config.n_head
    head_dim = n_embd // n_head
    scale = np.float32(1.0 / math.sqrt(head_dim))

    pos_indices = np.minimum(np.arange(seq_len), config.context - 1)
    x = tensors["tok.weight"][token_ids] + tensors["pos.weight"][pos_indices][None, :, :]

    causal_mask = np.triu(np.full((seq_len, seq_len), -1e9, dtype=np.float32), k=1)[None, None, :, :]

    for layer in range(config.n_layer):
        prefix = f"blocks.{layer}"
        ln1_out, _ = _layer_norm_fwd(x, tensors[f"{prefix}.ln1.weight"], tensors[f"{prefix}.ln1.bias"])
        qkv = ln1_out @ tensors[f"{prefix}.qkv.weight"].T + tensors[f"{prefix}.qkv.bias"]
        q = qkv[:, :, :n_embd].reshape(bsz, seq_len, n_head, head_dim).transpose(0, 2, 1, 3)
        k = qkv[:, :, n_embd : 2 * n_embd].reshape(bsz, seq_len, n_head, head_dim).transpose(0, 2, 1, 3)
        v = qkv[:, :, 2 * n_embd :].reshape(bsz, seq_len, n_head, head_dim).transpose(0, 2, 1, 3)

        scores = (q @ k.transpose(0, 1, 3, 2)) * scale + causal_mask
        scores_max = np.max(scores, axis=-1, keepdims=True)
        exp_scores = np.exp(scores - scores_max)
        probs = exp_scores / np.sum(exp_scores, axis=-1, keepdims=True)
        attended = (probs @ v).transpose(0, 2, 1, 3).reshape(bsz, seq_len, n_embd)

        proj = attended @ tensors[f"{prefix}.proj.weight"].T + tensors[f"{prefix}.proj.bias"]
        x = x + proj

        ln2_out, _ = _layer_norm_fwd(x, tensors[f"{prefix}.ln2.weight"], tensors[f"{prefix}.ln2.bias"])
        fc = ln2_out @ tensors[f"{prefix}.fc.weight"].T + tensors[f"{prefix}.fc.bias"]
        act = _gelu(fc)
        out = act @ tensors[f"{prefix}.out.weight"].T + tensors[f"{prefix}.out.bias"]
        x = x + out

    ln_f_out, _ = _layer_norm_fwd(x, tensors["ln_f.weight"], tensors["ln_f.bias"])
    logits = ln_f_out @ tensors["tok.weight"].T
    return logits


def score_candidates_numpy(
    bundle: SafetensorsBundle,
    tensors: Dict[str, np.ndarray],
    context: str,
    candidates: Sequence[str],
) -> List[float]:
    """Compute per-character mean log-probability for each candidate given `context`."""
    if not candidates:
        return []
    longest = max(len(c) for c in candidates)
    pref = prefix_tokens(context, longest, bundle.config, bundle.char_to_id)
    results: List[float] = []
    for cand in candidates:
        tail = encode_text(cand, bundle.char_to_id)
        if not tail:
            results.append(float("-inf"))
            continue
        limit = max(1, bundle.config.context - len(pref))
        tail = tail[:limit]
        seq = np.array([pref + tail[:-1]], dtype=np.int64)
        logits = numpy_forward_logits(seq, bundle.config, tensors)[0]  # (T, V)
        # Positions len(pref)-1 .. len(pref)+len(tail)-2 predict tail[0..len(tail)-1]
        start_pos = len(pref) - 1
        total_logp = 0.0
        for step, target_id in enumerate(tail):
            row = logits[start_pos + step]
            m = float(np.max(row))
            log_norm = m + math.log(float(np.sum(np.exp(row - m))))
            total_logp += float(row[target_id]) - log_norm
        results.append(total_logp / len(tail))
    return results


def numpy_forward_and_backward(
    token_ids: np.ndarray,
    loss_mask: np.ndarray,
    config: ModelConfig,
    tensors: Dict[str, np.ndarray],
    base_logits: Optional[np.ndarray] = None,
    kl_coef: float = 0.0,
    min_train_layer: int = 0,
) -> Tuple[float, Dict[str, np.ndarray]]:
    """
    Full analytical forward + backward pass in pure NumPy.
    `token_ids`: (B, T) int64
    `loss_mask`: (B, T-1) float32 mask for valid next-token targets
    """
    bsz, seq_len = token_ids.shape
    n_embd = config.n_embd
    n_head = config.n_head
    head_dim = n_embd // n_head
    scale = np.float32(1.0 / math.sqrt(head_dim))

    pos_indices = np.minimum(np.arange(seq_len), config.context - 1)
    x = tensors["tok.weight"][token_ids] + tensors["pos.weight"][pos_indices][None, :, :]

    causal_mask = np.triu(np.full((seq_len, seq_len), -1e9, dtype=np.float32), k=1)[None, None, :, :]
    block_caches = []

    for layer in range(config.n_layer):
        prefix = f"blocks.{layer}"
        ln1_out, ln1_cache = _layer_norm_fwd(x, tensors[f"{prefix}.ln1.weight"], tensors[f"{prefix}.ln1.bias"])
        qkv = ln1_out @ tensors[f"{prefix}.qkv.weight"].T + tensors[f"{prefix}.qkv.bias"]
        q = qkv[:, :, :n_embd].reshape(bsz, seq_len, n_head, head_dim).transpose(0, 2, 1, 3)
        k = qkv[:, :, n_embd : 2 * n_embd].reshape(bsz, seq_len, n_head, head_dim).transpose(0, 2, 1, 3)
        v = qkv[:, :, 2 * n_embd :].reshape(bsz, seq_len, n_head, head_dim).transpose(0, 2, 1, 3)

        scores = (q @ k.transpose(0, 1, 3, 2)) * scale + causal_mask
        scores_max = np.max(scores, axis=-1, keepdims=True)
        exp_scores = np.exp(scores - scores_max)
        probs = exp_scores / np.sum(exp_scores, axis=-1, keepdims=True)
        attended = (probs @ v).transpose(0, 2, 1, 3).reshape(bsz, seq_len, n_embd)

        proj = attended @ tensors[f"{prefix}.proj.weight"].T + tensors[f"{prefix}.proj.bias"]
        x_mid = x + proj

        ln2_out, ln2_cache = _layer_norm_fwd(x_mid, tensors[f"{prefix}.ln2.weight"], tensors[f"{prefix}.ln2.bias"])
        fc = ln2_out @ tensors[f"{prefix}.fc.weight"].T + tensors[f"{prefix}.fc.bias"]
        act = _gelu(fc)
        out = act @ tensors[f"{prefix}.out.weight"].T + tensors[f"{prefix}.out.bias"]
        x = x_mid + out

        block_caches.append((ln1_out, ln1_cache, q, k, v, probs, attended, ln2_out, ln2_cache, fc, act))

    ln_f_out, ln_f_cache = _layer_norm_fwd(x, tensors["ln_f.weight"], tensors["ln_f.bias"])
    logits = ln_f_out @ tensors["tok.weight"].T  # (B, T, V)

    # Next-token prediction loss on positions 0..T-2 predicting 1..T-1
    pred_logits = logits[:, :-1, :]  # (B, T-1, V)
    targets = token_ids[:, 1:]  # (B, T-1)
    max_logits = np.max(pred_logits, axis=-1, keepdims=True)
    shifted = pred_logits - max_logits
    exp_logits = np.exp(shifted)
    sum_exp = np.sum(exp_logits, axis=-1, keepdims=True)
    probs_vocab = exp_logits / sum_exp
    log_probs = shifted - np.log(sum_exp)

    b_idx = np.arange(bsz)[:, None]
    t_idx = np.arange(seq_len - 1)[None, :]
    nll = -log_probs[b_idx, t_idx, targets]
    denom = np.maximum(np.sum(loss_mask), 1.0)
    ce_loss = float(np.sum(nll * loss_mask) / denom)

    grad_pred = probs_vocab.copy()
    grad_pred[b_idx, t_idx, targets] -= 1.0
    grad_pred *= (loss_mask[:, :, None] / denom)

    total_loss = ce_loss
    if base_logits is not None and kl_coef > 0.0:
        base_pred = base_logits[:, :-1, :]
        base_max = np.max(base_pred, axis=-1, keepdims=True)
        base_shifted = base_pred - base_max
        base_exp = np.exp(base_shifted)
        base_sum = np.sum(base_exp, axis=-1, keepdims=True)
        base_probs = base_exp / base_sum
        base_log_probs = base_shifted - np.log(base_sum)
        kl_per_pos = np.sum(base_probs * (base_log_probs - log_probs), axis=-1)
        kl_loss = float(np.sum(kl_per_pos * loss_mask) / denom)
        total_loss += kl_coef * kl_loss
        grad_pred += kl_coef * (probs_vocab - base_probs) * (loss_mask[:, :, None] / denom)

    grad_logits = np.zeros_like(logits)
    grad_logits[:, :-1, :] = grad_pred

    grads: Dict[str, np.ndarray] = {}
    flat_grad_logits = grad_logits.reshape(-1, config.vocab)
    flat_ln_f_out = ln_f_out.reshape(-1, n_embd)

    # Grad from tied output projection: logits = ln_f_out @ tok.weight.T
    grad_tok_weight = flat_grad_logits.T @ flat_ln_f_out  # (V, C)
    grad_ln_f_out = (flat_grad_logits @ tensors["tok.weight"]).reshape(bsz, seq_len, n_embd)

    grad_x, grads["ln_f.weight"], grads["ln_f.bias"] = _layer_norm_bwd(grad_ln_f_out, ln_f_cache)

    for layer in range(config.n_layer - 1, -1, -1):
        prefix = f"blocks.{layer}"
        ln1_out, ln1_cache, q, k, v, probs, attended, ln2_out, ln2_cache, fc, act = block_caches[layer]

        flat_gx = grad_x.reshape(-1, n_embd)
        flat_act = act.reshape(-1, 4 * n_embd)
        if layer >= min_train_layer:
            grads[f"{prefix}.out.weight"] = flat_gx.T @ flat_act
            grads[f"{prefix}.out.bias"] = np.sum(flat_gx, axis=0)
        grad_act = (flat_gx @ tensors[f"{prefix}.out.weight"]).reshape(bsz, seq_len, 4 * n_embd)
        grad_fc = _gelu_grad(fc, grad_act)

        flat_gfc = grad_fc.reshape(-1, 4 * n_embd)
        flat_ln2 = ln2_out.reshape(-1, n_embd)
        if layer >= min_train_layer:
            grads[f"{prefix}.fc.weight"] = flat_gfc.T @ flat_ln2
            grads[f"{prefix}.fc.bias"] = np.sum(flat_gfc, axis=0)
        grad_ln2_out = (flat_gfc @ tensors[f"{prefix}.fc.weight"]).reshape(bsz, seq_len, n_embd)
        grad_ln2_x, gw2, gb2 = _layer_norm_bwd(grad_ln2_out, ln2_cache)
        if layer >= min_train_layer:
            grads[f"{prefix}.ln2.weight"] = gw2
            grads[f"{prefix}.ln2.bias"] = gb2

        grad_x_mid = grad_x + grad_ln2_x
        flat_gx_mid = grad_x_mid.reshape(-1, n_embd)
        flat_att = attended.reshape(-1, n_embd)
        if layer >= min_train_layer:
            grads[f"{prefix}.proj.weight"] = flat_gx_mid.T @ flat_att
            grads[f"{prefix}.proj.bias"] = np.sum(flat_gx_mid, axis=0)
        grad_attended = (flat_gx_mid @ tensors[f"{prefix}.proj.weight"]).reshape(bsz, seq_len, n_head, head_dim).transpose(0, 2, 1, 3)

        grad_probs = grad_attended @ v.transpose(0, 1, 3, 2)
        grad_v = probs.transpose(0, 1, 3, 2) @ grad_attended
        grad_scores = probs * (grad_probs - np.sum(grad_probs * probs, axis=-1, keepdims=True)) * scale
        grad_q = grad_scores @ k
        grad_k = grad_scores.transpose(0, 1, 3, 2) @ q

        gq = grad_q.transpose(0, 2, 1, 3).reshape(bsz, seq_len, n_embd)
        gk = grad_k.transpose(0, 2, 1, 3).reshape(bsz, seq_len, n_embd)
        gv = grad_v.transpose(0, 2, 1, 3).reshape(bsz, seq_len, n_embd)
        grad_qkv = np.concatenate([gq, gk, gv], axis=-1)

        flat_gqkv = grad_qkv.reshape(-1, 3 * n_embd)
        flat_ln1 = ln1_out.reshape(-1, n_embd)
        if layer >= min_train_layer:
            grads[f"{prefix}.qkv.weight"] = flat_gqkv.T @ flat_ln1
            grads[f"{prefix}.qkv.bias"] = np.sum(flat_gqkv, axis=0)
        grad_ln1_out = (flat_gqkv @ tensors[f"{prefix}.qkv.weight"]).reshape(bsz, seq_len, n_embd)
        grad_ln1_x, gw1, gb1 = _layer_norm_bwd(grad_ln1_out, ln1_cache)
        if layer >= min_train_layer:
            grads[f"{prefix}.ln1.weight"] = gw1
            grads[f"{prefix}.ln1.bias"] = gb1

        grad_x = grad_x_mid + grad_ln1_x
        if layer == min_train_layer and min_train_layer > 0:
            break

    if min_train_layer == 0:
        np.add.at(grad_tok_weight, token_ids, grad_x)
        grad_pos = np.zeros_like(tensors["pos.weight"])
        np.add.at(grad_pos, pos_indices, np.sum(grad_x, axis=0))
        grads["pos.weight"] = grad_pos

    grads["tok.weight"] = grad_tok_weight
    return total_loss, grads


# ---------------------------------------------------------------------------
# Corpus & Rime Dictionary Parsing
# ---------------------------------------------------------------------------

def load_training_texts(
    corpus_paths: Sequence[pathlib.Path],
    rime_dicts: Sequence[pathlib.Path],
    extra_phrases: Sequence[str],
    min_chars: int = 2,
    min_dict_weight: int = 0,
) -> List[str]:
    texts: List[str] = []
    for phrase in extra_phrases:
        cleaned = phrase.strip()
        if len(cleaned) >= min_chars:
            texts.append(cleaned)

    for path in corpus_paths:
        for raw_line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw_line.strip()
            if len(line) >= min_chars and not line.startswith("#"):
                texts.append(line)

    for dict_path in rime_dicts:
        in_header = False
        for raw_line in dict_path.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            if line == "---":
                in_header = True
                continue
            if in_header:
                if line == "...":
                    in_header = False
                continue
            parts = line.split("\t")
            word = parts[0].strip()
            if len(word) < min_chars:
                continue
            if len(parts) >= 3:
                try:
                    weight = int(parts[2].strip())
                    if weight < min_dict_weight:
                        continue
                except ValueError:
                    pass
            texts.append(word)

    return texts


def build_batches(
    texts: Sequence[str],
    bundle: SafetensorsBundle,
    batch_size: int,
    rng: np.random.Generator,
) -> List[Tuple[np.ndarray, np.ndarray]]:
    encoded_list: List[List[int]] = []
    max_tokens = bundle.config.context
    for text in texts:
        ids = [BOS_TOKEN] + encode_text(text, bundle.char_to_id, limit=max_tokens - 1)
        if len(ids) >= 3:
            encoded_list.append(ids)
    if not encoded_list:
        raise ValueError("No valid training sequences (>= 2 characters) found")

    indices = np.arange(len(encoded_list))
    rng.shuffle(indices)
    batches: List[Tuple[np.ndarray, np.ndarray]] = []
    for start in range(0, len(indices), batch_size):
        batch_idx = indices[start : start + batch_size]
        seqs = [encoded_list[int(i)] for i in batch_idx]
        max_len = max(len(s) for s in seqs)
        token_arr = np.zeros((len(seqs), max_len), dtype=np.int64)
        mask_arr = np.zeros((len(seqs), max_len - 1), dtype=np.float32)
        for row, seq in enumerate(seqs):
            token_arr[row, : len(seq)] = seq
            mask_arr[row, : len(seq) - 1] = 1.0
        batches.append((token_arr, mask_arr))
    return batches


# ---------------------------------------------------------------------------
# Continual Pretraining Loops (NumPy & PyTorch)
# ---------------------------------------------------------------------------

def train_numpy(
    bundle: SafetensorsBundle,
    texts: Sequence[str],
    steps: int,
    batch_size: int,
    lr: float,
    weight_decay: float,
    kl_coef: float,
    min_train_layer: int,
    seed: int,
) -> Dict[str, np.ndarray]:
    rng = np.random.default_rng(seed)
    params = {k: v.copy() for k, v in bundle.tensors.items()}
    frozen = {k: v.copy() for k, v in bundle.tensors.items()} if kl_coef > 0.0 else None

    m_state: Dict[str, np.ndarray] = {}
    v_state: Dict[str, np.ndarray] = {}
    beta1, beta2, eps = 0.9, 0.999, 1e-8

    batches = build_batches(texts, bundle, batch_size, rng)
    step = 0
    t0 = time.perf_counter()
    while step < steps:
        for token_arr, mask_arr in batches:
            if step >= steps:
                break
            step += 1
            base_logits = (
                numpy_forward_logits(token_arr, bundle.config, frozen)
                if frozen is not None and kl_coef > 0.0
                else None
            )
            loss, grads = numpy_forward_and_backward(
                token_arr,
                mask_arr,
                bundle.config,
                params,
                base_logits=base_logits,
                kl_coef=kl_coef,
                min_train_layer=min_train_layer,
            )

            # Global gradient norm clipping (max_norm = 1.0)
            sq_norm = sum(float(np.sum(g * g)) for g in grads.values())
            grad_norm = math.sqrt(sq_norm)
            clip_scale = min(1.0, 1.0 / max(grad_norm, 1e-6))

            # Cosine LR schedule with 10% warmup
            warmup = max(1, steps // 10)
            if step <= warmup:
                cur_lr = lr * (step / warmup)
            else:
                progress = (step - warmup) / max(1, steps - warmup)
                cur_lr = lr * 0.5 * (1.0 + math.cos(math.pi * progress))

            for name, grad in grads.items():
                g = grad * np.float32(clip_scale)
                if name not in m_state:
                    m_state[name] = np.zeros_like(params[name])
                    v_state[name] = np.zeros_like(params[name])
                m = m_state[name] = np.float32(beta1) * m_state[name] + np.float32(1.0 - beta1) * g
                v = v_state[name] = np.float32(beta2) * v_state[name] + np.float32(1.0 - beta2) * (g * g)
                m_hat = m / np.float32(1.0 - beta1**step)
                v_hat = v / np.float32(1.0 - beta2**step)
                if weight_decay > 0.0 and name.endswith(".weight") and params[name].ndim == 2:
                    params[name] -= np.float32(cur_lr * weight_decay) * params[name]
                params[name] -= np.float32(cur_lr) * (m_hat / (np.sqrt(v_hat) + np.float32(eps)))

            if step == 1 or step % max(1, steps // 10) == 0 or step == steps:
                elapsed = time.perf_counter() - t0
                print(
                    f"[NumPy] step {step:4d}/{steps} | loss={loss:.4f} | lr={cur_lr:.2e} | "
                    f"grad_norm={grad_norm:.3f} | {elapsed:.2f}s"
                )
        batches = build_batches(texts, bundle, batch_size, rng)

    return params


def train_torch(
    bundle: SafetensorsBundle,
    texts: Sequence[str],
    steps: int,
    batch_size: int,
    lr: float,
    weight_decay: float,
    kl_coef: float,
    min_train_layer: int,
    seed: int,
    device_name: str,
) -> Dict[str, np.ndarray]:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    torch.manual_seed(seed)
    device = torch.device(device_name if device_name != "auto" else ("cuda" if torch.cuda.is_available() else "cpu"))

    class TorchSentenceGPT(nn.Module):
        def __init__(self, cfg: ModelConfig, init_tensors: Dict[str, np.ndarray]) -> None:
            super().__init__()
            self.cfg = cfg
            self.params_dict = nn.ParameterDict()
            for name, arr in init_tensors.items():
                safe_key = name.replace(".", "__")
                self.params_dict[safe_key] = nn.Parameter(torch.from_numpy(arr.copy()))

        def _p(self, name: str) -> torch.Tensor:
            return self.params_dict[name.replace(".", "__")]

        def forward(self, token_ids: torch.Tensor) -> torch.Tensor:
            bsz, seq_len = token_ids.shape
            n_embd = self.cfg.n_embd
            n_head = self.cfg.n_head
            head_dim = n_embd // n_head

            pos_idx = torch.clamp(torch.arange(seq_len, device=token_ids.device), max=self.cfg.context - 1)
            x = F.embedding(token_ids, self._p("tok.weight")) + self._p("pos.weight")[pos_idx].unsqueeze(0)

            for layer in range(self.cfg.n_layer):
                pfx = f"blocks.{layer}"
                ln1 = F.layer_norm(x, (n_embd,), self._p(f"{pfx}.ln1.weight"), self._p(f"{pfx}.ln1.bias"), 1e-5)
                qkv = F.linear(ln1, self._p(f"{pfx}.qkv.weight"), self._p(f"{pfx}.qkv.bias"))
                q, k, v = qkv.split(n_embd, dim=-1)
                q = q.view(bsz, seq_len, n_head, head_dim).transpose(1, 2)
                k = k.view(bsz, seq_len, n_head, head_dim).transpose(1, 2)
                v = v.view(bsz, seq_len, n_head, head_dim).transpose(1, 2)
                att = F.scaled_dot_product_attention(q, k, v, is_causal=True)
                att = att.transpose(1, 2).contiguous().view(bsz, seq_len, n_embd)
                x = x + F.linear(att, self._p(f"{pfx}.proj.weight"), self._p(f"{pfx}.proj.bias"))
                ln2 = F.layer_norm(x, (n_embd,), self._p(f"{pfx}.ln2.weight"), self._p(f"{pfx}.ln2.bias"), 1e-5)
                fc = F.linear(ln2, self._p(f"{pfx}.fc.weight"), self._p(f"{pfx}.fc.bias"))
                act = F.gelu(fc, approximate="none")
                x = x + F.linear(act, self._p(f"{pfx}.out.weight"), self._p(f"{pfx}.out.bias"))

            x = F.layer_norm(x, (n_embd,), self._p("ln_f.weight"), self._p("ln_f.bias"), 1e-5)
            return F.linear(x, self._p("tok.weight"))

    model = TorchSentenceGPT(bundle.config, bundle.tensors).to(device)
    for safe_key, param in model.params_dict.items():
        orig_name = safe_key.replace("__", ".")
        if orig_name.startswith("blocks."):
            layer_idx = int(orig_name.split(".")[1])
            if layer_idx < min_train_layer:
                param.requires_grad_(False)
        elif orig_name == "pos.weight" and min_train_layer > 0:
            param.requires_grad_(False)

    frozen_model = None
    if kl_coef > 0.0:
        frozen_model = TorchSentenceGPT(bundle.config, bundle.tensors).to(device)
        frozen_model.eval()
        for p in frozen_model.parameters():
            p.requires_grad_(False)

    trainable_params = [p for p in model.parameters() if p.requires_grad]
    optimizer = torch.optim.AdamW(trainable_params, lr=lr, weight_decay=weight_decay)

    rng = np.random.default_rng(seed)
    batches = build_batches(texts, bundle, batch_size, rng)
    step = 0
    t0 = time.perf_counter()
    while step < steps:
        for token_arr, mask_arr in batches:
            if step >= steps:
                break
            step += 1
            tokens_t = torch.from_numpy(token_arr).to(device)
            mask_t = torch.from_numpy(mask_arr).to(device)

            logits = model(tokens_t)
            pred_logits = logits[:, :-1, :]
            targets = tokens_t[:, 1:]
            log_probs = F.log_softmax(pred_logits, dim=-1)
            nll = -log_probs.gather(-1, targets.unsqueeze(-1)).squeeze(-1)
            denom = mask_t.sum().clamp_min(1.0)
            loss = (nll * mask_t).sum() / denom

            if frozen_model is not None and kl_coef > 0.0:
                with torch.no_grad():
                    base_logits = frozen_model(tokens_t)[:, :-1, :]
                    base_probs = F.softmax(base_logits, dim=-1)
                    base_log_probs = F.log_softmax(base_logits, dim=-1)
                kl = (base_probs * (base_log_probs - log_probs)).sum(dim=-1)
                loss = loss + kl_coef * ((kl * mask_t).sum() / denom)

            optimizer.zero_grad()
            loss.backward()
            grad_norm = float(torch.nn.utils.clip_grad_norm_(trainable_params, 1.0))

            warmup = max(1, steps // 10)
            if step <= warmup:
                cur_lr = lr * (step / warmup)
            else:
                progress = (step - warmup) / max(1, steps - warmup)
                cur_lr = lr * 0.5 * (1.0 + math.cos(math.pi * progress))
            for pg in optimizer.param_groups:
                pg["lr"] = cur_lr

            optimizer.step()
            if step == 1 or step % max(1, steps // 10) == 0 or step == steps:
                elapsed = time.perf_counter() - t0
                print(
                    f"[Torch/{device}] step {step:4d}/{steps} | loss={float(loss.item()):.4f} | "
                    f"lr={cur_lr:.2e} | grad_norm={grad_norm:.3f} | {elapsed:.2f}s"
                )
        batches = build_batches(texts, bundle, batch_size, rng)

    return {
        safe_key.replace("__", "."): param.detach().cpu().numpy().copy()
        for safe_key, param in model.params_dict.items()
    }


# ---------------------------------------------------------------------------
# Contextual Policy & Diagnostic Evaluation
# ---------------------------------------------------------------------------

def contextual_best_index(conditioned: Sequence[float], unconditioned: Sequence[float], min_boost: float = 1.0) -> int:
    if len(conditioned) != len(unconditioned) or len(conditioned) < 2:
        return 0
    selected = 0
    best_score = max(conditioned[0], unconditioned[0])
    for idx in range(1, len(conditioned)):
        advantage = conditioned[idx] - conditioned[0]
        prior_advantage = unconditioned[idx] - unconditioned[0]
        self_boost = conditioned[idx] - unconditioned[idx]
        calibrated = conditioned[idx] - max(0.0, prior_advantage)
        if calibrated > best_score and advantage > prior_advantage and self_boost >= min_boost:
            selected = idx
            best_score = calibrated
    return selected


def print_probe_report(
    bundle: SafetensorsBundle,
    tensors: Dict[str, np.ndarray],
    title: str,
    extra_probes: Sequence[str] = (),
) -> None:
    probes: List[Tuple[str, List[str]]] = [
        ("交叉", ["编译", "贬义", "变圆", "边缘", "便于"]),
        ("还在", ["派对", "排队"]),
        ("生日", ["派对", "排队"]),
        ("重庆和成都", ["直接", "之间"]),
        ("可以", ["直接", "之间"]),
    ]
    for item in extra_probes:
        if ":" in item:
            ctx, rest = item.split(":", 1)
            cands = [c.strip() for c in rest.split(",") if c.strip()]
            if cands:
                probes.append((ctx.strip(), cands))
    print(f"\n=== {title} ===")
    for ctx, cands in probes:
        uncond = score_candidates_numpy(bundle, tensors, "", cands)
        cond = score_candidates_numpy(bundle, tensors, ctx, cands)
        winner = contextual_best_index(cond, uncond)
        parts = []
        for idx, cand in enumerate(cands):
            boost = cond[idx] - uncond[idx]
            marker = "*" if idx == winner else " "
            parts.append(f"{marker}{cand}(u={uncond[idx]:.2f}, c={cond[idx]:.2f}, d={boost:+.2f})")
        print(f"  上文 [{ctx}] -> 首选 [{cands[winner]}] | " + "  ".join(parts))


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Continual pretraining and INT8 safetensors export for chinese-ime-lm."
    )
    parser.add_argument(
        "--model",
        type=pathlib.Path,
        default=pathlib.Path(__file__).resolve().parents[1] / "model" / "sentence-model.safetensors",
        help="Path to input sentence-model.safetensors",
    )
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        default=None,
        help="Path to write fine-tuned .safetensors (omit for dry-run / evaluation only)",
    )
    parser.add_argument(
        "--corpus",
        type=pathlib.Path,
        action="append",
        default=[],
        help="Plain text file(s) with one sentence/phrase per line",
    )
    parser.add_argument(
        "--rime-dict",
        type=pathlib.Path,
        action="append",
        default=[],
        help="Rime .dict.yaml file(s) to extract phrases from",
    )
    parser.add_argument(
        "--phrase",
        type=str,
        action="append",
        default=[],
        help="Individual phrase(s) to include in continual pretraining (repeatable)",
    )
    parser.add_argument(
        "--probe",
        type=str,
        action="append",
        default=[],
        help="Custom probe in '上文:候选1,候选2' format (repeatable)",
    )
    parser.add_argument("--steps", type=int, default=60, help="Number of optimizer steps")
    parser.add_argument("--batch-size", type=int, default=16, help="Mini-batch size")
    parser.add_argument("--lr", type=float, default=1.5e-4, help="Peak learning rate")
    parser.add_argument("--weight-decay", type=float, default=0.01, help="AdamW weight decay")
    parser.add_argument(
        "--kl-coef",
        type=float,
        default=0.25,
        help="KL regularization weight against frozen base model to prevent forgetting",
    )
    parser.add_argument(
        "--min-train-layer",
        type=int,
        default=4,
        help="Lowest Transformer block index to update (0 = all 6 layers, 4 = top 2 layers + head)",
    )
    parser.add_argument(
        "--backend",
        choices=["auto", "torch", "numpy"],
        default="auto",
        help="Training backend ('auto' uses PyTorch if installed, otherwise NumPy)",
    )
    parser.add_argument("--device", type=str, default="auto", help="PyTorch device (auto, cuda, cpu)")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = parse_args(argv)
    bundle = load_safetensors(args.model)
    print(
        f"Loaded {args.model.name}: vocab={bundle.config.vocab}, "
        f"layers={bundle.config.n_layer}, heads={bundle.config.n_head}, "
        f"embd={bundle.config.n_embd}, context={bundle.config.context}"
    )
    print_probe_report(bundle, bundle.tensors, "初始模型基准评分 (Before Continual Pretraining)", args.probe)

    texts = load_training_texts(args.corpus, args.rime_dict, args.phrase)
    if not texts:
        if args.output is not None:
            save_safetensors(args.output, bundle, bundle.tensors, quantize_int8=True)
            print(f"\nNo training texts specified; re-exported quantized model to {args.output}")
        return 0

    backend = args.backend
    if backend == "auto":
        try:
            import torch  # noqa: F401
            backend = "torch"
        except ImportError:
            backend = "numpy"

    print(
        f"\nStarting continual pretraining on {len(texts)} phrases/sentences "
        f"(backend={backend}, steps={args.steps}, lr={args.lr}, kl_coef={args.kl_coef}, "
        f"min_train_layer={args.min_train_layer})..."
    )

    if backend == "torch":
        updated_tensors = train_torch(
            bundle=bundle,
            texts=texts,
            steps=args.steps,
            batch_size=args.batch_size,
            lr=args.lr,
            weight_decay=args.weight_decay,
            kl_coef=args.kl_coef,
            min_train_layer=args.min_train_layer,
            seed=args.seed,
            device_name=args.device,
        )
    else:
        updated_tensors = train_numpy(
            bundle=bundle,
            texts=texts,
            steps=args.steps,
            batch_size=args.batch_size,
            lr=args.lr,
            weight_decay=args.weight_decay,
            kl_coef=args.kl_coef,
            min_train_layer=args.min_train_layer,
            seed=args.seed,
        )

    print_probe_report(bundle, updated_tensors, "微调后 FP32 评分 (After Continual Pretraining)", args.probe)

    if args.output is not None:
        save_safetensors(args.output, bundle, updated_tensors, quantize_int8=True)
        reloaded = load_safetensors(args.output)
        print_probe_report(reloaded, reloaded.tensors, f"INT8 重导出验证 ({args.output.name})", args.probe)
        print(f"\nSaved INT8 quantized model to: {args.output} ({args.output.stat().st_size:,} bytes)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
