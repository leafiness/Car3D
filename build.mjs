// 「3D 车况（原生）」的打包脚本：node build.mjs [--install]  → out/3D 车况（原生） v<版本>.apk。说明见 README.md。
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { tools, build } from './apkbuild.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
await build({
  SRC: HERE,
  OUT: path.join(HERE, 'build'),
  APK_DIR: path.join(HERE, 'out'),
  KS: path.join(HERE, 'release.keystore'),
  ASSETS: path.join(HERE, 'assets'),
  noCompress: ["ttf","glb"], // 不压缩的扩展名：系统直接映射着读，不用整个解压进内存
  tk: tools({ BT: "D:/Program Files/WorkBuddyCache/apk_work/build-tools/android-14", JAR: "D:/Program Files/WorkBuddyCache/apk_work/sdk/platforms/android-34/android.jar", JDK: "D:/Program Files/WorkBuddyCache/apk_work/jdk/jdk-17.0.20.1+1" }),
  install: process.argv.includes('--install'),
});
