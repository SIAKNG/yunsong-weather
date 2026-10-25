# 发布与签名

公开工程只构建 `userApi`，不含内置 API 版或签名私钥。
执行 `:app:assembleUserApiRelease` 后，在本机使用 Android SDK 的 `zipalign`、`apksigner` 完成对齐验证和签名。
保持今后更新的应用包名及签名证书不变，并递增 `versionCode`。

## 1.2 正式签名

- 包名：`com.example.weatherapp.userapi`
- 应用名称：云松天气；版本标识：1.2-userapi；Android 数字版本号：7。
- 发布证书 SHA-256：`4cbd6193380941fad84db288909761d5022402a029e41f3ecabeabcb1862ed40`。
- 该摘要是公开的证书指纹，不是签名私钥。
- 以前的本机测试版本使用另一签名，正式签名版无法直接覆盖安装。
  不要直接卸载旧版；先在应用中确认和保存自己填写的配置。

## 发布清单

1. 确认素材授权及第三方声明，无真实凭证、私人图像、私钥或本机记录。
   公开源码及其历史不得包含原第三方 SVG；私有素材仅可通过本机仓库外目录合并。
2. 运行 `python tools/check_public_tree.py` 和单元测试，再编译 Release。
3. 在受保护的本机签名，验证签名、对齐、版本和隐私边界。
4. 在 GitHub Releases 创建对应版本，附 APK、SHA-256 和更新说明。
5. 保留对应源码提交/标签；没有 GitHub 验证结果时，不宣称远端 CI 已通过。

密钥及口令应备份在可信的离线位置或密码管理器。丢失发布私钥可能导致无法发布可覆盖安装的后续更新。
不要把签名文件或口令上传到仓库、Issue、Release 或公开网盘。
