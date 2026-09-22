import { sensor } from '@kit.SensorServiceKit';
import { LocalSettings } from '../data/LocalSettings';

/**
 * 摇一摇检测(对应 Android 版 sensor/ShakeDetector.kt)。
 *
 * 加速度传感器:三轴合加速度超过阈值即触发一次,带 1.5 秒防抖;
 * 由设置页开关控制(GameScreen 里按开关启停)。
 */
export class ShakeDetector {
  private static readonly THRESHOLD: number = 22.0;   // m/s² 合加速度阈值
  private static readonly DEBOUNCE_MS: number = 1500;

  private callback: () => void;
  private lastTriggerAt: number = 0;
  private running: boolean = false;

  constructor(callback: () => void) {
    this.callback = callback;
  }

  /** 是否可用(设备有加速度传感器)。 */
  static available(): boolean {
    try {
      return sensor.getSensorListSync()
        .some((info: sensor.Sensor) => info.sensorId === sensor.SensorId.ACCELEROMETER);
    } catch (e) {
      return false;
    }
  }

  start(): void {
    if (this.running || !LocalSettings.shakeToResetEnabled()) {
      return;
    }
    this.running = true;
    try {
      sensor.on(sensor.SensorId.ACCELEROMETER, (data: sensor.AccelerometerResponse) => {
        const magnitude: number = Math.sqrt(data.x * data.x + data.y * data.y + data.z * data.z);
        const now: number = Date.now();
        if (magnitude > ShakeDetector.THRESHOLD
          && now - this.lastTriggerAt > ShakeDetector.DEBOUNCE_MS) {
          this.lastTriggerAt = now;
          this.callback();
        }
      }, { interval: 100_000_000 }); // 100ms
    } catch (e) {
      this.running = false;
    }
  }

  stop(): void {
    if (!this.running) {
      return;
    }
    this.running = false;
    try {
      sensor.off(sensor.SensorId.ACCELEROMETER);
    } catch (e) {
      // 忽略
    }
  }
}
