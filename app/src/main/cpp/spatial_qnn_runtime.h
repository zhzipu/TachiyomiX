#ifndef MIHON_SPATIAL_QNN_RUNTIME_H
#define MIHON_SPATIAL_QNN_RUNTIME_H

#include <cstdint>
#include <string>
#include <vector>

namespace spatial_qnn {

struct TensorData {
  std::string name;
  std::vector<uint32_t> dimensions;
  std::vector<float> values;
};

class Runtime {
public:
  Runtime();
  ~Runtime();
  Runtime(const Runtime &) = delete;
  Runtime &operator=(const Runtime &) = delete;

  bool initialize();
  bool compile_dlc(const std::string &dlc_path,
                   const std::string &binary_path);
  bool load(const std::string &binary_path);
  bool execute(const std::vector<TensorData> &inputs,
               std::vector<TensorData> &outputs);
  bool execute_to_files(const std::vector<TensorData> &inputs,
                        const std::string &output_directory);
  void unload_graph();
  void reset();

  // 设置目标 HTP 代次（69/73/75/79/81），用于显式构造 QNN 设备配置
  void set_htp_architecture(int arch);

  // true：使用 HTP(DSP) 后端；false：使用 CPU 后端（零售机 DSP 受限时的回退方案）
  void set_use_htp(bool use_htp);

  const std::vector<TensorData> &input_metadata() const;
  const std::vector<TensorData> &output_metadata() const;
  const std::string &error() const;

  static std::string safe_tensor_filename(const std::string &name);

private:
  class Impl;
  Impl *impl_;
};

} // namespace spatial_qnn

#endif
