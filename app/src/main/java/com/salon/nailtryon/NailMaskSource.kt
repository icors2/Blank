package com.salon.nailtryon

/** Which pipeline produced the nail mask for the current preview. */
enum class NailMaskSource {
    /** No hand / no processed image yet */
    None,

    /** `nail_seg.tflite` produced a mask bitmap */
    SegmentationModel,

    /** Landmark ovals (toggle off, no model, or TFLite returned null) */
    Landmarks,
}
