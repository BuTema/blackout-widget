# Публікація в Google Play: що зробити

## 1. Один раз
1. Створити акаунт розробника: https://play.google.com/console/signup ($25, верифікація особи документом).
   Для **особистого** акаунта, створеного після листопада 2023, перед виходом у продакшн треба **закрите
   тестування: щонайменше 12 тестерів, які залишаються в тесті 14 днів поспіль**.
2. Згенерувати ключ завантаження (upload key). Зберегти файл і паролі в надійному місці (менеджер паролів):
   ```
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 upload.jks > upload.jks.b64     # на macOS: base64 -i upload.jks
   ```
3. У GitHub: репозиторій → Settings → Secrets and variables → Actions → New repository secret:
   - `UPLOAD_KEYSTORE_B64` — вміст `upload.jks.b64`
   - `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS` (`upload`), `UPLOAD_KEY_PASSWORD`
   Після цього кожен білд у вкладці Actions → артефакт `blackout-widget-aab` міститиме підписаний `.aab`.
4. GitHub Pages: Settings → Pages → Source: Deploy from a branch → `main` / `/docs`.
   Політика конфіденційності буде за адресою https://butema.github.io/blackout-widget/

## 2. Створення застосунку в Play Console
- Create app: назва «Графіки світла», мова за замовчуванням — українська, App, Free.
- **App signing**: залишити Play App Signing (за замовчуванням), завантажити `.aab` підписаний upload-ключем.
- Тексти: `store/uk/listing.md`, `store/en/listing.md`.
- Графіка: іконка 512×512 і feature graphic 1024×500 — `store/graphics/`. Скріншоти телефону (2–8 шт.) зробіть самі,
  із вигаданими назвами місць (не вашу справжню адресу).
- Privacy policy URL: адреса GitHub Pages із п. 1.4.
- Контактний e-mail — обов'язковий і публічний у картці застосунку.

## 3. Декларації (відповіді)
- **Data safety.** Дані не збираються розробником. Введена адреса (вулиця й будинок) надсилається сайту оператора
  (стороння сторона) для надання функції. Безпечно відповісти: «Збирає чи передає дані» → Так →
  Location → Approximate/Precise не потрібні; «Personal info → Address» — Shared, лише для функціональності застосунку,
  необов'язково, шифрується в дорозі (HTTPS), видалення — видалити місце або застосунок.
  Реклама: ні. Акаунт: ні.
- **Content rating**: анкета без насильства/азартних ігор тощо → «для всіх».
- **Target audience**: 18+ (або «усі, крім дітей до 13» — не обирайте дитячу аудиторію).
- **Ads**: ні. **News app / health / finance / government**: ні (застосунок неофіційний і не представляє
  державний орган: це зазначено в описі).
- **Exact alarms** (SCHEDULE_EXACT_ALARM): якщо Play Console запитає, пояснення — «нагадування про початок/кінець
  відключення світла в обраний користувачем час; без дозволу працює неточний режим».
- **Foreground service / повноекранні сповіщення**: не використовуються.

## 4. Перший випуск
1. Testing → Closed testing → Create track → завантажити `.aab` → додати тестерів (e-mail-и Google) → Rollout.
2. Через 14 днів з 12+ тестерами: Production → Apply for access → Create release.
3. Далі кожен новий `.aab` з Actions має більший `versionCode` автоматично (номер запуску CI).

## Важливо
- Версія з Play підписана іншим ключем, ніж APK з GitHub Releases: щоб перейти з одної на іншу, спершу видаліть застосунок.
- Після публікації не змінюйте `applicationId` (`com.artem.blackoutwidget`).
