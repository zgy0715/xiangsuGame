#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""题库预热 CLI(演示前可手动跑,避免首次请求现场生成):
  cd server && python seed_puzzles.py
可选: --difficulty EASY|MEDIUM|HARD(默认三档全补), --target N(默认 config.BANK_TARGET)
"""
import argparse

import content
import db
from config import BANK_TARGET


def main():
    parser = argparse.ArgumentParser(description="预热题库")
    parser.add_argument("--difficulty", choices=list(BANK_TARGET), default=None)
    parser.add_argument("--target", type=int, default=None)
    args = parser.parse_args()

    db.init_db()
    conn = db.connect()
    try:
        difficulties = [args.difficulty] if args.difficulty else list(BANK_TARGET)
        for d in difficulties:
            target = args.target or BANK_TARGET[d]
            content.ensure_bank(conn, d, target)
            print(f"{d}: 现有 {content.bank_count(conn, d)} 题")
    finally:
        conn.close()
    print("题库预热完成 ✔")


if __name__ == "__main__":
    main()
