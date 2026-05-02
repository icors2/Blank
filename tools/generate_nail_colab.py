#!/usr/bin/env python3
"""Generate docs/nail_segmentation_colab_outline.ipynb"""
import json
from pathlib import Path


def cell_md(text: str) -> dict:
    lines = text.split("\n")
    src = [ln + "\n" for ln in lines[:-1]]
    if lines:
        src.append(lines[-1])
    return {"cell_type": "markdown", "metadata": {}, "source": src}


def cell_code(text: str) -> dict:
    lines = text.split("\n")
    src = [ln + "\n" for ln in lines[:-1]]
    if lines:
        src.append(lines[-1])
    return {
        "cell_type": "code",
        "metadata": {},
        "source": src,
        "execution_count": None,
        "outputs": [],
    }


INTRO = r"""# Nail segmentation → TensorFlow Lite (Colab)

**Goal:** Train a binary nail mask (**DeepLabV3+**, ResNet50 + ASPP) and export **FP16 TFLite** for Android (`NailSegmentationHelper`).

**Data layout (typical Kaggle / Roboflow):**
```
DATA_ROOT/
  train/images/   *.jpg
  train/masks/    *.png   # 0 = background, 255 = nail
  valid/images/
  valid/masks/
  test/images/    # optional
  test/masks/
```

With **`USE_OFFICIAL_SPLITS = True`**, train/val come from **`train/`** + **`valid|val/`**. With **`False`**, or when only a flat **`images`/`masks`** tree exists, the notebook **discovers** folders and applies **`VAL_SPLIT`**.

**Flow:** setup → pairs → `tf.data` (brightness/contrast aug on train) → Dice+BCE → **phase-1** (frozen backbone) → **phase-2** (fine-tune) → **FP16 TFLite** (`nail_seg_fp16.tflite`; **`nail_seg.tflite`** is a duplicate for assets) → verify shapes.

**Android:** Input remains **[0, 1] float32 NHWC RGB**; the saved model applies **×255** + **`resnet50.preprocess_input`** internally. TensorFlow Lite FP16 kernels often still expose **float32** I/O buffers — verify dtypes in Section 10."""

CODE_SETUP = r"""# ==========================================
# [LABEL: Section 1 & 2 - Setup and Config]
# ==========================================
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers
import numpy as np
from pathlib import Path
import matplotlib.pyplot as plt
from typing import Tuple, Optional, List

print("TensorFlow", tf.__version__)

# Model input dimensions — must match Android resize / ByteBuffer element count
IMG_SIZE = 256
BATCH_SIZE = 8  # use 4 if GPU OOM (DeepLabV3+ + ResNet50)
EPOCHS = 60
VAL_SPLIT = 0.15
USE_OFFICIAL_SPLITS = True
SEED = 42
PHASE1_EPOCHS = 15

tf.keras.utils.set_random_seed(SEED)

DATA_ROOT = Path("/content/nail-data")
IMAGE_DIR = None
MASK_DIR = None"""

CODE_DATA = r"""# ==========================================
# [LABEL: Section 2b & 3 - Kaggle API and Directory Mapping]
# ==========================================
import os
import shutil
import subprocess
import zipfile

# !pip install -q kaggle

KAGGLE_DATASET = "muhammadhammad261/nail-segmentation-dataset"
NAIL_DATA = Path("/content/nail-data")

try:
    from google.colab import files
except ImportError:
    files = None

if not Path("/root/.kaggle/kaggle.json").exists():
    if files is None:
        print("No /root/.kaggle/kaggle.json — place it or run in Colab and upload when prompted.")
    else:
        print("Upload kaggle.json from Kaggle → Account → API")
        up = files.upload()
        os.makedirs("/root/.kaggle", exist_ok=True)
        shutil.move("kaggle.json", "/root/.kaggle/kaggle.json")
        os.chmod("/root/.kaggle/kaggle.json", 0o600)

if Path("/root/.kaggle/kaggle.json").exists():
    NAIL_DATA.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["kaggle", "datasets", "download", "-d", KAGGLE_DATASET, "-p", str(NAIL_DATA), "--unzip"],
        check=True,
    )
    for z in list(NAIL_DATA.rglob("*.zip")):
        try:
            with zipfile.ZipFile(z, "r") as zf:
                zf.extractall(z.parent)
            z.unlink(missing_ok=True)
        except zipfile.BadZipFile:
            pass
    DATA_ROOT = NAIL_DATA
    print("DATA_ROOT =", DATA_ROOT)

# ---------- Directory mapping ----------
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp"}

IMAGE_DIR_HINTS = (
    "images",
    "Images",
    "image",
    "JPEGImages",
    "imgs",
)
MASK_DIR_HINTS = (
    "masks",
    "Masks",
    "mask",
    "SegmentationClass",
    "labels",
    "Labels",
)


def count_pairs_between(img_dir: Path, mask_dir: Path) -> int:
    return len(list_pairs(img_dir, mask_dir))


def discover_pair_dirs(root: Path) -> Tuple[Path, Path]:
    subs = [root]
    try:
        subs.extend([p for p in root.rglob("*") if p.is_dir()])
    except OSError:
        pass
    best = (None, None, -1)
    for parent in subs:
        dirs = [p for p in parent.iterdir() if p.is_dir()]
        for img_candidate in dirs:
            name_low = img_candidate.name.lower()
            if not any(
                h.lower() == name_low or h.lower() in name_low for h in IMAGE_DIR_HINTS
            ):
                continue
            for mask_candidate in dirs:
                if mask_candidate == img_candidate:
                    continue
                mn = mask_candidate.name.lower()
                if not any(h.lower() == mn or h.lower() in mn for h in MASK_DIR_HINTS):
                    continue
                n = count_pairs_between(img_candidate, mask_candidate)
                if n > best[2]:
                    best = (img_candidate, mask_candidate, n)
    if best[2] <= 0:
        raise RuntimeError(
            "Could not find matching images/masks under DATA_ROOT. "
            "Try: !find DATA_ROOT -type d | head -50"
        )
    print("Discovered IMAGE_DIR:", best[0])
    print("Discovered MASK_DIR:", best[1])
    print("Matching pairs:", best[2])
    return best[0], best[1]


def list_pairs(image_dir: Path, mask_dir: Path):
    pairs = []
    for p in sorted(image_dir.iterdir()):
        if p.suffix.lower() not in IMAGE_EXT:
            continue
        stem = p.stem
        m = None
        for ext in (".png", ".jpg", ".jpeg"):
            cand = mask_dir / f"{stem}{ext}"
            if cand.exists():
                m = cand
                break
        if m is None:
            cand_guess = mask_dir / (stem + "_mask.png")
            if cand_guess.exists():
                m = cand_guess
        if m is None:
            continue
        pairs.append((str(p), str(m)))
    return pairs


def resolve_validation_dir(root: Path) -> Optional[Path]:
    for name in ("valid", "val", "validation"):
        d = root / name
        if d.is_dir():
            return d
    return None


pairs_train: list
pairs_val: list
pairs_test: Optional[List[tuple]] = None

root = Path(DATA_ROOT)
train_root = root / "train"
val_root = resolve_validation_dir(root)

if USE_OFFICIAL_SPLITS and train_root.is_dir() and val_root is not None:
    ti, tm = train_root / "images", train_root / "masks"
    vi, vm = val_root / "images", val_root / "masks"
    assert ti.is_dir() and tm.is_dir(), f"Missing {ti} or {tm}"
    assert vi.is_dir() and vm.is_dir(), f"Missing {vi} or {vm}"
    pairs_train = list_pairs(ti, tm)
    pairs_val = list_pairs(vi, vm)
    print("Using official splits: train", len(pairs_train), "valid", len(pairs_val))
    test_root = root / "test"
    if test_root.is_dir():
        tei, tem = test_root / "images", test_root / "masks"
        if tei.is_dir() and tem.is_dir():
            pairs_test = list_pairs(tei, tem)
            print("test pairs:", len(pairs_test))
elif USE_OFFICIAL_SPLITS and train_root.is_dir() and val_root is None:
    raise RuntimeError(
        "Found train/ but no valid/ or val/. Add a validation split or set "
        "USE_OFFICIAL_SPLITS = False for a single tree + VAL_SPLIT."
    )
elif IMAGE_DIR is not None and MASK_DIR is not None:
    pairs_all = list_pairs(Path(IMAGE_DIR), Path(MASK_DIR))
    n_val = max(1, int(len(pairs_all) * VAL_SPLIT))
    pairs_train = pairs_all[:-n_val]
    pairs_val = pairs_all[-n_val:]
    print("Manual dirs — split", len(pairs_train), "train", len(pairs_val), "val")
else:
    IMAGE_DIR, MASK_DIR = discover_pair_dirs(root)
    pairs_all = list_pairs(IMAGE_DIR, MASK_DIR)
    n_val = max(1, int(len(pairs_all) * VAL_SPLIT))
    pairs_train = pairs_all[:-n_val]
    pairs_val = pairs_all[-n_val:]
    print("Discovered flat layout — split", len(pairs_train), "train", len(pairs_val), "val")

assert len(pairs_train) > 0 and len(pairs_val) > 0, "Need train and val pairs"
"""

CODE_PIPE = r"""# ==========================================
# [LABEL: Section 4 - tf.data Pipeline and Augmentation]
# ==========================================
def load_sample(img_path, mask_path):
    img_data = tf.io.read_file(img_path)
    img = tf.image.decode_image(img_data, channels=3, expand_animations=False)
    img = tf.image.convert_image_dtype(img, tf.float32)

    mask_raw = tf.io.read_file(mask_path)
    mask_u8 = tf.image.decode_png(mask_raw, channels=1)
    mask = tf.cast(mask_u8, tf.float32) / 255.0
    mask = tf.clip_by_value(mask, 0.0, 1.0)

    img = tf.image.resize(img, [IMG_SIZE, IMG_SIZE], method="bilinear")
    mask = tf.image.resize(mask, [IMG_SIZE, IMG_SIZE], method="nearest")
    mask = tf.clip_by_value(mask, 0.0, 1.0)
    return img, mask


def augment(img, mask):
    seed = tf.random.uniform([2], maxval=10_000, dtype=tf.int32)

    img = tf.image.stateless_random_flip_left_right(img, seed=seed)
    mask = tf.image.stateless_random_flip_left_right(mask, seed=seed)

    img = tf.image.stateless_random_brightness(img, max_delta=0.2, seed=seed)
    img = tf.image.stateless_random_contrast(img, lower=0.8, upper=1.2, seed=seed)
    img = tf.clip_by_value(img, 0.0, 1.0)

    return img, mask


def make_ds(paths_pairs, training: bool):
    ips = [a for a, _ in paths_pairs]
    mps = [b for _, b in paths_pairs]
    ds = tf.data.Dataset.from_tensor_slices((ips, mps))
    if training:
        ds = ds.shuffle(min(500, len(paths_pairs)), seed=SEED, reshuffle_each_iteration=True)
    ds = ds.map(load_sample, num_parallel_calls=tf.data.AUTOTUNE)
    if training:
        ds = ds.map(augment, num_parallel_calls=tf.data.AUTOTUNE)
    return ds.batch(BATCH_SIZE).prefetch(tf.data.AUTOTUNE)


train_ds = make_ds(pairs_train, training=True)
val_ds = make_ds(pairs_val, training=False)

test_ds = None
if pairs_test is not None and len(pairs_test) > 0:
    test_ds = make_ds(pairs_test, training=False)
    print("test_ds samples:", len(pairs_test))

print("train:", len(pairs_train), "val:", len(pairs_val))
"""

CODE_LOSS_MODEL = r"""# ==========================================
# [LABEL: Section 5 & 6 - Loss Functions and Model Build]
# ==========================================
def dice_loss(y_true, y_pred, smooth=1e-6):
    y_true_f = tf.reshape(y_true, [tf.shape(y_true)[0], -1])
    y_pred_f = tf.reshape(y_pred, [tf.shape(y_pred)[0], -1])
    inter = tf.reduce_sum(y_true_f * y_pred_f, axis=-1)
    denom = tf.reduce_sum(y_true_f, axis=-1) + tf.reduce_sum(y_pred_f, axis=-1)
    dice = (2.0 * inter + smooth) / (denom + smooth)
    return 1.0 - tf.reduce_mean(dice)


def combined_loss(y_true, y_pred):
    bce = tf.keras.losses.binary_crossentropy(y_true, y_pred)
    return tf.reduce_mean(bce) + dice_loss(y_true, y_pred)


def iou_metric(y_true, y_pred):
    pred_bin = tf.cast(y_pred > 0.5, tf.float32)
    inter = tf.reduce_sum(y_true * pred_bin, axis=[1, 2, 3])
    union = tf.reduce_sum(tf.maximum(y_true, pred_bin), axis=[1, 2, 3])
    iou = inter / (union + 1e-6)
    return tf.reduce_mean(iou)


class ResNet50Preprocess(keras.layers.Layer):
    def call(self, x):
        return keras.applications.resnet50.preprocess_input(x)


def deeplab_convolution_block(
    block_input,
    num_filters=256,
    kernel_size=3,
    dilation_rate=1,
    use_bias=False,
):
    x = layers.Conv2D(
        num_filters,
        kernel_size=kernel_size,
        dilation_rate=dilation_rate,
        padding="same",
        use_bias=use_bias,
        kernel_initializer=keras.initializers.HeNormal(),
    )(block_input)
    x = layers.BatchNormalization()(x)
    return layers.Activation("relu")(x)


def deeplab_dilated_spatial_pyramid_pooling(dspp_input):
    dims = dspp_input.shape
    x = layers.AveragePooling2D(pool_size=(dims[-3], dims[-2]))(dspp_input)
    x = deeplab_convolution_block(x, kernel_size=1, use_bias=True)
    out_pool = layers.UpSampling2D(
        size=(dims[-3] // x.shape[1], dims[-2] // x.shape[2]),
        interpolation="bilinear",
    )(x)

    out_1 = deeplab_convolution_block(dspp_input, kernel_size=1, dilation_rate=1)
    out_6 = deeplab_convolution_block(dspp_input, kernel_size=3, dilation_rate=6)
    out_12 = deeplab_convolution_block(dspp_input, kernel_size=3, dilation_rate=12)
    out_18 = deeplab_convolution_block(dspp_input, kernel_size=3, dilation_rate=18)

    x = layers.Concatenate(axis=-1)([out_pool, out_1, out_6, out_12, out_18])
    return deeplab_convolution_block(x, kernel_size=1)


def build_model(img_size: int) -> keras.Model:
    assert img_size % 32 == 0, "IMG_SIZE must be divisible by 32 (e.g. 256)"

    model_input = keras.Input(shape=(img_size, img_size, 3))
    scaled_rgb = layers.Rescaling(scale=255.0)(model_input)
    preprocessed = ResNet50Preprocess(name="resnet_preprocess")(scaled_rgb)

    resnet50 = keras.applications.ResNet50(
        weights="imagenet",
        include_top=False,
        input_tensor=preprocessed,
        name="resnet50_backbone",
    )

    x = resnet50.get_layer("conv4_block6_2_relu").output
    x = deeplab_dilated_spatial_pyramid_pooling(x)

    input_a = layers.UpSampling2D(
        size=(img_size // 4 // x.shape[1], img_size // 4 // x.shape[2]),
        interpolation="bilinear",
    )(x)
    input_b = resnet50.get_layer("conv2_block3_2_relu").output
    input_b = deeplab_convolution_block(input_b, num_filters=48, kernel_size=1)

    x = layers.Concatenate(axis=-1)([input_a, input_b])
    x = deeplab_convolution_block(x)
    x = deeplab_convolution_block(x)
    x = layers.UpSampling2D(
        size=(img_size // x.shape[1], img_size // x.shape[2]),
        interpolation="bilinear",
    )(x)

    out = layers.Conv2D(
        1,
        kernel_size=(1, 1),
        padding="same",
        activation="sigmoid",
        dtype="float32",
        name="nail_prob",
    )(x)
    return keras.Model(model_input, out, name="nail_seg_deeplabv3plus")


model = build_model(IMG_SIZE)
model.summary()

dummy = tf.zeros((1, IMG_SIZE, IMG_SIZE, 3))
assert model(dummy).shape == (1, IMG_SIZE, IMG_SIZE, 1)
"""

CODE_TRAIN = r"""# ==========================================
# [LABEL: Section 7 - Phase 1 and Phase 2 Training]
# ==========================================

print("Starting Phase 1: freezing ResNet50 backbone")
backbone = model.get_layer("resnet50_backbone")
backbone.trainable = False

model.compile(
    optimizer=keras.optimizers.Adam(learning_rate=1e-3),
    loss=combined_loss,
    metrics=[iou_metric],
)

history_phase1 = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=PHASE1_EPOCHS,
)

print("Starting Phase 2: fine-tuning entire model")
backbone.trainable = True

model.compile(
    optimizer=keras.optimizers.Adam(learning_rate=1e-5),
    loss=combined_loss,
    metrics=[iou_metric],
)

callbacks = [
    keras.callbacks.EarlyStopping(
        patience=10, restore_best_weights=True, monitor="val_iou_metric", mode="max"
    ),
    keras.callbacks.ModelCheckpoint(
        "best_nail.keras", save_best_only=True, monitor="val_iou_metric", mode="max"
    ),
]

history_phase2 = model.fit(
    train_ds,
    validation_data=val_ds,
    epochs=EPOCHS,
    callbacks=callbacks,
)
"""

CODE_VIS = r"""def show_val_sample(idx=0):
    for imgs, masks in val_ds.take(1):
        pred = model.predict(imgs[:1], verbose=0)
        plt.figure(figsize=(12, 4))
        plt.subplot(1, 3, 1)
        plt.imshow(imgs[idx].numpy())
        plt.title("Image")
        plt.axis("off")
        plt.subplot(1, 3, 2)
        plt.imshow(masks[idx].numpy().squeeze(), vmin=0, vmax=1, cmap="gray")
        plt.title("GT mask")
        plt.axis("off")
        plt.subplot(1, 3, 3)
        plt.imshow(pred[idx].squeeze(), vmin=0, vmax=1, cmap="gray")
        plt.title("Predicted")
        plt.axis("off")
        plt.tight_layout()
        plt.show()
        break


def eval_test_set():
    if test_ds is None:
        print("No test_ds.")
        return
    r = model.evaluate(test_ds, verbose=0)
    print("Test loss / metrics:", r)


# show_val_sample()
# eval_test_set()
"""

CODE_EXPORT = r"""# ==========================================
# [LABEL: Section 9 - FP16 TFLite Export]
# ==========================================
import os

OUT_DIR = "saved_model_nail"
saved_ok = False

try:
    model.export(OUT_DIR)
    saved_ok = os.path.isdir(OUT_DIR)
except (AttributeError, TypeError, ValueError) as e:
    print("model.export failed:", e)

if not saved_ok:
    try:
        model.save(OUT_DIR, save_format="tf")
        saved_ok = os.path.isdir(OUT_DIR)
    except Exception as e:
        print("SavedModel export skipped:", e)

if saved_ok:
    converter = tf.lite.TFLiteConverter.from_saved_model(OUT_DIR)
else:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)

converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]

tflite_bytes = converter.convert()
open("nail_seg_fp16.tflite", "wb").write(tflite_bytes)
open("nail_seg.tflite", "wb").write(tflite_bytes)
print("Wrote nail_seg_fp16.tflite / nail_seg.tflite", len(tflite_bytes), "bytes")
"""

CODE_VERIFY = r"""interp = tf.lite.Interpreter(model_content=tflite_bytes)
interp.allocate_tensors()
inn = interp.get_input_details()[0]
out = interp.get_output_details()[0]
print("INPUT", inn["shape"], inn["dtype"])
print("OUTPUT", out["shape"], out["dtype"])

test_in = np.zeros((1, IMG_SIZE, IMG_SIZE, 3), dtype=np.float32)
interp.set_tensor(inn["index"], test_in)
interp.invoke()
print("out sample", interp.get_tensor(out["index"]).shape)
"""


def main():
    out_path = Path(__file__).resolve().parent.parent / "docs" / "nail_segmentation_colab_outline.ipynb"
    cells = [
        cell_md(INTRO),
        cell_md("## Section 1 & 2 — Setup and config\n\nConstants and imports. Run Section 2b next unless **`DATA_ROOT`** already contains your dataset."),
        cell_code(CODE_SETUP),
        cell_md(
            "## Section 2b & 3 — Kaggle download (optional) and directory mapping\n\n"
            "Upload **`kaggle.json`** when prompted if missing. "
            "Then builds **`pairs_train` / `pairs_val`** (official splits, manual dirs, or discovered flat layout)."
        ),
        cell_code(CODE_DATA),
        cell_md(
            "## Section 4 — `tf.data` pipeline and augmentation\n\n"
            "Brightness/contrast augmentation matches typical phone camera variation."
        ),
        cell_code(CODE_PIPE),
        cell_md("## Section 5 & 6 — Losses and DeepLabV3+"),
        cell_code(CODE_LOSS_MODEL),
        cell_md(
            "## Section 7 — Phase 1 and phase 2 training\n\n"
            "Phase 1: **`PHASE1_EPOCHS`** with frozen backbone. Phase 2: fine-tune with callbacks."
        ),
        cell_code(CODE_TRAIN),
        cell_md("## Section 8 — Visual check (optional)"),
        cell_code(CODE_VIS),
        cell_md(
            "## Section 9 — FP16 TFLite export\n\n"
            "Also writes **`nail_seg.tflite`** (duplicate bytes) for **`app/src/main/assets/`**."
        ),
        cell_code(CODE_EXPORT),
        cell_md("**Fallback:** If SavedModel fails, **`from_keras_model`** still converts."),
        cell_md("## Section 10 — Verify interpreter"),
        cell_code(CODE_VERIFY),
        cell_md(
            "## Section 11 — Ship to Android\n\n"
            "1. Download **`nail_seg.tflite`** from Colab.\n"
            "2. **`app/src/main/assets/nail_seg.tflite`**\n"
            "3. RGB float **[0, 1]** — aligned with this notebook."
        ),
    ]
    nb = {
        "nbformat": 4,
        "nbformat_minor": 5,
        "metadata": {
            "colab": {"provenance": [], "toc_visible": True},
            "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
            "language_info": {"name": "python"},
        },
        "cells": cells,
    }
    out_path.write_text(json.dumps(nb, indent=2))
    print("Wrote", out_path, "cells=", len(cells))


if __name__ == "__main__":
    main()
