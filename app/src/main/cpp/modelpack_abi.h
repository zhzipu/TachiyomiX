/*
 * 模型包原生推理库 ABI（libmodelpack.so）
 *
 * 模型包可以（可选地）在 `lib/<abi>/libmodelpack.so` 里带自己的推理实现。
 * 宿主 dlopen 该库、校验 `modelpack_abi_version()` 后，用 `modelpack_init` /
 * `modelpack_process` / `modelpack_release` 驱动它；没有任何一个符号时，
 * 该模型包回退到宿主内置引擎，行为与不带 .so 时完全一致。
 *
 * 约定：
 * - 全部函数使用 C 调用约定，导出名固定，不导出 C++ 符号；
 * - 库可以实现多个模型：`modelpack_init` 的 `model_key` 对应描述符
 *   (`assets/modelpack.json`) 里的 `models[].key`，`options_json` 是宿主侧参数：
 *   {"model","noise","scale","precision","fp16Arithmetic","tileSize","tileSleepMs","backend","style"}
 * - `modelpack_process` 的输入/输出都是紧密排布或带 stride 的 RGBA8888 缓冲，
 *   输出尺寸必须等于 `scale * 输入尺寸`（宿主据此分配输出 bitmap）；
 *   返回 0 表示成功，非 0 表示失败（宿主放弃本页增强）；
 * - 宿主保证同一时刻只有一个线程调用 process。
 *
 * 版本演进：ABI 不兼容变更时必须递增 MODELPACK_ABI_VERSION，
 * 版本不匹配时宿主直接忽略该库。
 */
#ifndef TACHIYOMIX_MODELPACK_ABI_H
#define TACHIYOMIX_MODELPACK_ABI_H

#define MODELPACK_ABI_VERSION 1

#ifdef __cplusplus
extern "C" {
#endif

/* 返回本库实现的 ABI 版本，必须等于 MODELPACK_ABI_VERSION。 */
int modelpack_abi_version(void);

/*
 * 可选：返回本库自描述的 JSON（格式与 assets/modelpack.json 相同）。
 * 纯原生模型包可以只依赖它来声明模型，宿主仅用于日志/诊断。
 */
const char *modelpack_describe(void);

/* 按描述符里的模型 key 初始化；返回 0 表示成功。 */
int modelpack_init(const char *model_key, const char *options_json);

/*
 * 处理一帧 RGBA8888 图像；返回 0 表示成功。
 * rgba_in / rgba_out 由宿主加锁后传入，stride 单位为字节。
 */
int modelpack_process(const unsigned char *rgba_in,
                      int width,
                      int height,
                      int stride_in,
                      unsigned char *rgba_out,
                      int stride_out);

/* 释放 modelpack_init 申请的资源。 */
void modelpack_release(void);

/*
 * 可选：模型包通过它把本帧处理进度（0-100）回报给宿主。
 * 宿主在 modelpack_init 前调用；回调在主处理线程上触发，
 * 宿主可用它驱动阅读器左下角“单页进度”条。未实现/未注册则宿主跳过。
 */
typedef void (*modelpack_progress_fn)(int percent);
void modelpack_set_progress_callback(modelpack_progress_fn progress);

#ifdef __cplusplus
}

/* 宿主加载器使用的函数指针类型，便于 dlopen/dlsym 后调用。 */
typedef int (*modelpack_abi_version_fn)(void);
typedef const char *(*modelpack_describe_fn)(void);
typedef int (*modelpack_init_fn)(const char *, const char *);
typedef int (*modelpack_process_fn)(const unsigned char *, int, int, int, unsigned char *, int);
typedef void (*modelpack_release_fn)(void);
typedef void (*modelpack_set_progress_callback_fn)(modelpack_progress_fn);

#endif /* __cplusplus */

#endif /* TACHIYOMIX_MODELPACK_ABI_H */
