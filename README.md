# Lumi

Trình phát video cá nhân cho Android: **không quảng cáo**, **phát nền khi tắt màn hình**.
Dự án học tập, dùng cá nhân, không thương mại.

## Cài lên điện thoại

**Cách 1 – qua cáp USB** (bật *Tuỳ chọn nhà phát triển → Gỡ lỗi USB* trên điện thoại):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat installDebug
```

**Cách 2 – copy file:** build xong, chép `app\build\outputs\apk\debug\app-debug.apk` sang điện thoại,
mở file và cho phép "Cài ứng dụng không rõ nguồn gốc".

Hoặc mở cả thư mục này bằng **Android Studio** rồi bấm ▶ Run.

## Cách hoạt động

```
Tìm kiếm ─► NewPipeExtractor ─► danh sách video
Bấm video ─► MediaController ──► PlaybackService (foreground service)
                                   ├─ StreamResolver: lấy link luồng gốc (video-only + audio)
                                   ├─ LumiMediaSourceFactory: dựng DASH + ghép 2 luồng
                                   └─ ExoPlayer + MediaSession ─► notification / màn hình khoá
```

| Tính năng | Vì sao làm được |
|---|---|
| Không quảng cáo | Phát thẳng file luồng từ googlevideo, không đi qua trình phát của YouTube |
| Nghe khi tắt màn hình | ExoPlayer nằm trong `MediaSessionService` (foreground service), không phụ thuộc Activity |
| Tiết kiệm dữ liệu khi tắt màn hình | `MainActivity.onStop()` tắt track video → chỉ tải audio |
| Điều khiển ngoài app | Media3 tự tạo notification, điều khiển màn hình khoá, tai nghe Bluetooth |
| Mở link từ app khác | Chia sẻ link video → chọn Lumi |
| Hàng chờ | Menu ⋮ ở kết quả: "Phát tiếp theo" / "Thêm vào hàng chờ"; ⏮ ⏭ trên trình phát + notification |
| Tự phát | Tới video cuối hàng chờ, service thêm sẵn 1 video liên quan chưa xem (bật/tắt được) |
| Chọn chất lượng | Mỗi video liệt kê mọi mức máy giải mã được; đổi giữa chừng vẫn giữ vị trí |
| Chất lượng mặc định | ⚙ Cài đặt → chọn 144p…2160p; không có thì lấy mức gần nhất thấp hơn |
| Trang chủ | Chip: Dành cho bạn (từ lịch sử xem), thịnh hành (Âm nhạc, Trò chơi, Trực tiếp...), chủ đề |
| Lịch sử | Tab "Bạn"; lưu trên máy, không cần tài khoản |
| Trang kênh | Bấm ảnh/tên kênh: ảnh bìa, số người đăng ký, mô tả, video của kênh |
| Theo dõi kênh | Nút "Theo dõi"; tab "Kênh đăng ký" gộp video mới (qua RSS, nhẹ) xếp theo thời gian |
| Nhập từ Google Takeout | Chọn file .zip / subscriptions.csv / .json; tự lấy ảnh kênh |
| Báo cập nhật thư viện | Mỗi ngày hỏi GitHub; có bản NewPipeExtractor mới → thông báo + dải báo ở Trang chủ |
| Từ khoá bị YouTube chặn | Vd "faptv": tự tìm lại bằng "faptv video" và ghi chú cho người dùng |
| Tự phục hồi | Link bị thu hồi (HTTP 403) → lấy link mới, phát tiếp đúng chỗ; video hỏng → bỏ qua |

## Cấu trúc mã

```
app/src/main/java/dev/lumi/player/
├── LumiApp.kt                     khởi tạo NewPipeExtractor + lịch sử
├── extractor/
│   ├── OkHttpDownloader.kt        lớp mạng cho NewPipeExtractor
│   ├── VideoRepository.kt         tìm kiếm, gợi ý từ khoá, mục thịnh hành, phân trang
│   ├── StreamResolver.kt          chọn luồng video/audio + thông tin chi tiết + video liên quan
│   ├── VideoItemCodec.kt          VideoItem ↔ Bundle / JSON
│   └── TimeText.kt                "5 tháng trước"
│   ├── ChannelRepository.kt       trang kênh, video của kênh, feed RSS
├── history/WatchHistory.kt        lịch sử xem (file JSON trên máy) + đề xuất "Dành cho bạn"
├── library/
│   ├── SubscriptionStore.kt       kênh đang theo dõi (file JSON trên máy)
│   └── TakeoutImporter.kt         đọc file Google Takeout (.zip / .csv / .json)
├── updates/ExtractorUpdates.kt    kiểm tra bản NewPipeExtractor mới (WorkManager) + thông báo
├── player/
│   ├── PlaybackService.kt         service phát nền, hàng chờ, tự phát, đổi chất lượng, ghi lịch sử
│   ├── LumiMediaSourceFactory.kt  MediaItem → MediaSource (DASH/HLS/merge)
│   ├── LumiSettings.kt            cài đặt (chất lượng mặc định, tự phát)
│   ├── Queue.kt                   khoá/lệnh dùng chung UI ↔ service
│   └── YoutubeRequestAdapter.kt   header/kiểu request cho từng client
└── ui/
    ├── MainActivity.kt            khung app: tab, màn phủ, kết nối service
    ├── Chrome.kt                  thanh trên, thanh dưới, mini-player
    ├── HomeScreen.kt / HomeViewModel.kt     Trang chủ + chip chủ đề
    ├── SearchScreen.kt / SearchViewModel.kt tìm kiếm + gợi ý
    ├── WatchScreen.kt             trang xem video
    ├── ChannelScreen.kt           trang kênh
    ├── SubscriptionsScreen.kt     tab "Kênh đăng ký" + nhập Takeout
    ├── YouScreen.kt               tab "Bạn" (lịch sử)
    ├── SettingsScreen.kt
    └── VideoComponents.kt         thẻ video, hàng video, ảnh kênh, menu ⋮
```

## Khi app đột nhiên không phát được nữa

YouTube thay đổi thường xuyên. Gần như mọi lỗi kiểu "HTTP 403", "không lấy được thông tin video"
được sửa bằng cách **cập nhật NewPipeExtractor** (app tự báo khi có bản mới — xem ⚙ Cài đặt):

1. Xem bản mới nhất: https://github.com/TeamNewPipe/NewPipeExtractor/releases
2. Sửa `newpipeExtractor = "v0.x.y"` trong `gradle/libs.versions.toml`
3. Build lại. Nếu báo lỗi biên dịch, thường là API trong `YoutubeRequestAdapter.kt` hoặc `StreamResolver.kt` đổi tên.

## Môi trường build

- Gradle 9.9.2 (tải từ GitHub mirror vì `services.gradle.org` chậm ở mạng này), AGP 9.5.1, Kotlin 2.4.21
- JDK: bản JBR đi kèm Android Studio (JDK 25)
- compileSdk 37.1, minSdk 26 (Android 8.0+)

## Chưa có (gợi ý làm tiếp)

- Kéo thả sắp xếp hàng chờ; lưu hàng chờ khi tắt app
- Chất lượng mặc định riêng cho Wi‑Fi và 4G
- Video «dành cho trẻ em» chưa phát được (giới hạn của NewPipeExtractor v0.27.1) — có nút mở bằng app YouTube
- Tách Shorts khỏi feed kênh đăng ký
- Picture-in-Picture, hẹn giờ tắt
- Tự lấy lại link khi link hết hạn (~6 giờ) nếu tạm dừng quá lâu
