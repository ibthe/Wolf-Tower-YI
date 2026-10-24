#!/usr/bin/env python3
"""
Генератор лицензионных ключей Wolf Tower (подпись ECDSA P-256 / SHA-256).

Установка:   pip install cryptography

1) Один раз создать пару ключей (на СВОЁМ компьютере, не в облаке):
     python keygen.py init
   Создаст private_key.pem (СЕКРЕТ, никому не отдавать и не коммитить в git)
   и выведет публичный ключ. Его надо вставить в
   android/app/src/main/assets/license_public_key.txt и пересобрать приложение.

2) Выпуск ключей:
     python keygen.py issue --days 30                 # на 30 дней
     python keygen.py issue --days 365 --device ABCD-EF01-2345   # привязка к устройству
     python keygen.py issue --perpetual               # бессрочный
     python keygen.py issue --trial --device ABCD-EF01-2345      # пробный, 7 дней
     python keygen.py issue --trial --days 3 --device ABCD-EF01-2345
   Пробный ключ (plan=trial) обязательно привязывается к устройству (--device), иначе его можно раздать.
   Автоматического пробного периода в приложении нет: без ключа оно не работает.
   Опции: --note "имя покупателя" (попадёт в ключ только для вашего учёта), --plan pro

3) Проверка ключа:
     python keygen.py verify <ключ>

Формат ключа: WT1.<payload base64url>.<подпись base64url>
"""
import argparse
import base64
import datetime
import json
import os
import secrets
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = os.path.dirname(os.path.abspath(__file__))
PRIVATE = os.environ.get("WT_PRIVATE_KEY", os.path.join(HERE, "private_key.pem"))


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64u_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def load_private():
    if not os.path.exists(PRIVATE):
        sys.exit(f"Нет {PRIVATE}. Сначала: python keygen.py init")
    with open(PRIVATE, "rb") as f:
        return serialization.load_pem_private_key(f.read(), password=None)


def public_b64(private) -> str:
    der = private.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return base64.b64encode(der).decode()


def cmd_init(_):
    if os.path.exists(PRIVATE):
        sys.exit(f"{PRIVATE} уже существует, не перезаписываю (иначе все выданные ключи перестанут работать).")
    private = ec.generate_private_key(ec.SECP256R1())
    pem = private.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    with open(PRIVATE, "wb") as f:
        f.write(pem)
    try:
        os.chmod(PRIVATE, 0o600)
    except OSError:
        pass
    print("Создан", PRIVATE, "(СЕКРЕТ: хранить только у себя, сделать резервную копию, не коммитить в git)")
    print("\nПубличный ключ для android/app/src/main/assets/license_public_key.txt:\n")
    print(public_b64(private))


def cmd_issue(args):
    private = load_private()
    today = datetime.date.today()
    if args.trial:
        if not args.device and not args.any_device:
            sys.exit("Пробный ключ нужно привязать к устройству: --device ID (или --any-device, если осознанно)")
        args.plan = "trial"
        if not args.days:
            args.days = 7
    if args.perpetual and args.trial:
        sys.exit("Пробный ключ не может быть бессрочным")
    if args.perpetual:
        exp = ""
    elif args.days:
        exp = (today + datetime.timedelta(days=args.days)).isoformat()
    else:
        sys.exit("Укажите --days N или --perpetual")
    payload = {
        "v": 1,
        "id": secrets.token_hex(4),
        "plan": args.plan,
        "iat": today.isoformat(),
        "exp": exp,
        "dev": (args.device or "").strip().upper(),
    }
    if args.note:
        payload["note"] = args.note
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    sig = private.sign(raw, ec.ECDSA(hashes.SHA256()))  # DER, как ждёт Java
    key = f"WT1.{b64u(raw)}.{b64u(sig)}"
    print(key)
    print(
        f"\n# id={payload['id']} plan={args.plan} до={exp or 'бессрочно'} "
        f"устройство={payload['dev'] or 'любое'}" + (f" note={args.note}" if args.note else ""),
        file=sys.stderr,
    )


def cmd_verify(args):
    private = load_private()
    parts = args.key.strip().split(".")
    if len(parts) != 3 or parts[0] != "WT1":
        sys.exit("Неверный формат")
    raw, sig = b64u_decode(parts[1]), b64u_decode(parts[2])
    try:
        private.public_key().verify(sig, raw, ec.ECDSA(hashes.SHA256()))
    except Exception:
        sys.exit("Подпись НЕ верна")
    print("Подпись верна:", raw.decode("utf-8"))


def main():
    ap = argparse.ArgumentParser(description="Wolf Tower license keys")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init").set_defaults(fn=cmd_init)
    p = sub.add_parser("issue")
    p.add_argument("--days", type=int)
    p.add_argument("--perpetual", action="store_true")
    p.add_argument("--device")
    p.add_argument("--plan", default="pro")
    p.add_argument("--trial", action="store_true", help="пробный ключ: plan=trial, по умолчанию 7 дней")
    p.add_argument("--any-device", action="store_true", help="разрешить пробный ключ без привязки")
    p.add_argument("--note")
    p.set_defaults(fn=cmd_issue)
    v = sub.add_parser("verify")
    v.add_argument("key")
    v.set_defaults(fn=cmd_verify)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
