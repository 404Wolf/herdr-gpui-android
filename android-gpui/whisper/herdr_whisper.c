// JNI shim for dev.herdr.android.dictation.LocalWhisper: one whisper.cpp context per model,
// English (base.en), greedy decoding, no timestamps, CPU only. Audio is PCM16 little-endian mono
// 16 kHz, as the app records it; it is converted to float here and never leaves the process.
// A transcription can be aborted from another thread (LocalWhisper.abort): whisper.cpp polls the
// flag between decoder steps; an abort that comes before a run stops that run, and the flag is
// cleared after each run. The text goes back as UTF-8 bytes (Whisper's segments may split a
// character, and emit ones Modified UTF-8 cannot carry): Java decodes them, with replacement.
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"

struct herdr_whisper {
    struct whisper_context *ctx;
    atomic_int abort;
};

static bool should_abort(void *data) {
    struct herdr_whisper *w = data;
    return atomic_load(&w->abort) != 0;
}

JNIEXPORT jlong JNICALL Java_dev_herdr_android_dictation_LocalWhisper_open(JNIEnv *env, jclass cls, jstring model) {
    (void) cls;
    const char *path = (*env)->GetStringUTFChars(env, model, NULL);
    if (path == NULL) return 0;
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    (*env)->ReleaseStringUTFChars(env, model, path);
    if (ctx == NULL) return 0;
    struct herdr_whisper *w = calloc(1, sizeof *w);
    if (w == NULL) {
        whisper_free(ctx);
        return 0;
    }
    w->ctx = ctx;
    atomic_init(&w->abort, 0);
    return (jlong) (intptr_t) w;
}

JNIEXPORT void JNICALL Java_dev_herdr_android_dictation_LocalWhisper_abort(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    struct herdr_whisper *w = (struct herdr_whisper *) (intptr_t) handle;
    if (w != NULL) atomic_store(&w->abort, 1);
}

JNIEXPORT void JNICALL Java_dev_herdr_android_dictation_LocalWhisper_close(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    struct herdr_whisper *w = (struct herdr_whisper *) (intptr_t) handle;
    if (w == NULL) return;
    whisper_free(w->ctx);
    free(w);
}

// The text of PCM16 samples as UTF-8 bytes, or NULL when whisper failed or was aborted.
static jbyteArray transcribe(JNIEnv *env, struct herdr_whisper *w, const int16_t *pcm, size_t n, int threads) {
    if (atomic_exchange(&w->abort, 0) != 0) return NULL;
    float *samples = malloc((n > 0 ? n : 1) * sizeof *samples);
    if (samples == NULL) return NULL;
    for (size_t i = 0; i < n; i++) samples[i] = (float) pcm[i] / 32768.0f;
    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : 4;
    p.language = "en";
    p.translate = false;
    p.no_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.abort_callback = should_abort;
    p.abort_callback_user_data = w;
    int rc = whisper_full(w->ctx, p, samples, (int) n);
    free(samples);
    if (atomic_exchange(&w->abort, 0) != 0 || rc != 0) return NULL;
    size_t cap = 256, len = 0;
    char *text = malloc(cap);
    if (text == NULL) return NULL;
    text[0] = '\0';
    int segments = whisper_full_n_segments(w->ctx);
    for (int i = 0; i < segments; i++) {
        const char *s = whisper_full_get_segment_text(w->ctx, i);
        size_t add = s == NULL ? 0 : strlen(s);
        if (len + add + 1 > cap) {
            while (len + add + 1 > cap) cap *= 2;
            char *grown = realloc(text, cap);
            if (grown == NULL) {
                free(text);
                return NULL;
            }
            text = grown;
        }
        memcpy(text + len, s, add);
        len += add;
        text[len] = '\0';
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) len);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) len, (const jbyte *) text);
    free(text);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_dev_herdr_android_dictation_LocalWhisper_transcribePcm(JNIEnv *env, jclass cls,
        jlong handle, jbyteArray pcm, jint length, jint threads) {
    (void) cls;
    struct herdr_whisper *w = (struct herdr_whisper *) (intptr_t) handle;
    // Never past the array: a length it does not hold is refused, not read.
    if (w == NULL || pcm == NULL || length < 0 || length > (*env)->GetArrayLength(env, pcm)) return NULL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, pcm, NULL);
    if (bytes == NULL) return NULL;
    jbyteArray out = transcribe(env, w, (const int16_t *) bytes, (size_t) length / 2, threads);
    (*env)->ReleaseByteArrayElements(env, pcm, bytes, JNI_ABORT);
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_dev_herdr_android_dictation_LocalWhisper_transcribeFile(JNIEnv *env, jclass cls,
        jlong handle, jstring file, jlong max_bytes, jint threads) {
    (void) cls;
    struct herdr_whisper *w = (struct herdr_whisper *) (intptr_t) handle;
    if (w == NULL || file == NULL || max_bytes < 0) return NULL;
    size_t max_samples = (size_t) max_bytes / 2;
    const char *path = (*env)->GetStringUTFChars(env, file, NULL);
    if (path == NULL) return NULL;
    FILE *f = fopen(path, "rb");
    (*env)->ReleaseStringUTFChars(env, file, path);
    if (f == NULL) return NULL;
    size_t cap = 1 << 20, n = 0;
    int16_t *pcm = malloc(cap * sizeof *pcm);
    while (pcm != NULL) {
        if (n > max_samples) {
            // Past the bound (a recording is at most 15 minutes): not read into memory.
            free(pcm);
            pcm = NULL;
            break;
        }
        if (n == cap) {
            int16_t *grown = realloc(pcm, cap * 2 * sizeof *pcm);
            if (grown == NULL) {
                free(pcm);
                pcm = NULL;
                break;
            }
            pcm = grown;
            cap *= 2;
        }
        size_t got = fread(pcm + n, sizeof *pcm, cap - n, f);
        n += got;
        if (got == 0) break;
    }
    fclose(f);
    if (pcm == NULL) return NULL;
    jbyteArray out = transcribe(env, w, pcm, n, threads);
    free(pcm);
    return out;
}
