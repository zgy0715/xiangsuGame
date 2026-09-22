"""
生成游戏音效资源:按钮点击 + 通关 + 检查出错提示音。
输出到 app/src/main/res/raw/ 目录(WAV 格式,Android 原生支持)。

用法: python tools/gen_audio.py
依赖: numpy + scipy(仅重生成音效时需要,App 本身不依赖)

注:背景音乐不由本脚本合成 —— 它是外部素材 `app/src/main/res/raw/bgm.mp3`
(来源见 README「素材来源」),由 BackgroundMusicManager 用 MediaPlayer 循环播放。
"""
import os

import numpy as np
from scipy.io import wavfile

OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
SAMPLE_RATE = 44100


def generate_click():
    """生成按钮点击音效:短促清脆的'滴'声,约 0.08 秒。"""
    duration = 0.08
    t = np.linspace(0, duration, int(SAMPLE_RATE * duration), endpoint=False)
    freq = 1200  # 高频
    env = np.exp(-t * 40)  # 快速衰减
    audio = 0.3 * env * np.sin(2 * np.pi * freq * t)
    audio = np.int16(audio / np.max(np.abs(audio)) * 32767)
    path = os.path.join(OUTPUT_DIR, "click.wav")
    wavfile.write(path, SAMPLE_RATE, audio)
    print(f"Generated: {path} ({duration}s)")


def generate_win():
    """生成通关庆祝音效:上升琶音 C E G C5,约 1.2 秒。"""
    duration = 1.2
    t = np.linspace(0, duration, int(SAMPLE_RATE * duration), endpoint=False)
    notes_freqs = [523.25, 659.25, 783.99, 1046.50]  # C5 E5 G5 C6
    note_dur = 0.15
    audio = np.zeros_like(t)
    for i, freq in enumerate(notes_freqs):
        start = i * note_dur
        end = start + 0.8  # 每个音延长
        mask = (t >= start) & (t < end)
        note_t = t[mask] - start
        env = np.exp(-note_t * 1.5)
        audio[mask] += 0.2 * env * np.sin(2 * np.pi * freq * note_t)

    audio = np.int16(audio / np.max(np.abs(audio)) * 32767 * 0.8)
    path = os.path.join(OUTPUT_DIR, "win.wav")
    wavfile.write(path, SAMPLE_RATE, audio)
    print(f"Generated: {path} ({duration}s)")


def generate_error():
    """生成错误提示音效:低沉短促'嘟'声,约 0.15 秒。"""
    duration = 0.15
    t = np.linspace(0, duration, int(SAMPLE_RATE * duration), endpoint=False)
    freq = 220  # 低频
    env = np.exp(-t * 12)
    audio = 0.3 * env * np.sin(2 * np.pi * freq * t)
    audio = np.int16(audio / np.max(np.abs(audio)) * 32767)
    path = os.path.join(OUTPUT_DIR, "error.wav")
    wavfile.write(path, SAMPLE_RATE, audio)
    print(f"Generated: {path} ({duration}s)")


if __name__ == "__main__":
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    generate_click()
    generate_win()
    generate_error()
    print("\n音效已生成到 res/raw/")
