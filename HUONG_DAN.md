# YOUTUBE PLAYER cho Android TV

Ứng dụng nghe nhạc YouTube trên TV, điều khiển bằng điện thoại (quét mã QR) hoặc bằng remote TV.
Dựng từ nền kỹ thuật của app Savify Karaoke (NewPipe Extractor + ExoPlayer + server NanoHTTPD + QR).
Cách build/cài đặt giống hệt app karaoke: đưa toàn bộ thư mục lên GitHub → Actions → **Build APK** → tải `youtube-player.apk`.

## Giao diện TV (3 cột)
- **Trái**: mã QR kết nối remote, nút *Tạo Thể Loại Mới*, danh mục âm nhạc (5 thể loại có sẵn + thể loại tự tạo), nút *Cài đặt*.
- **Giữa**: ô tìm kiếm (gõ bằng remote TV, nút micro để nói, hoặc dán link YouTube) và lưới kết quả.
- **Phải**: màn hình phát nhỏ (có nút phóng to), thanh tiến trình, nút điều khiển, hàng đợi tiếp theo.

## Phím remote TV
| Phím | Tác dụng |
|---|---|
| OK trên một bài | Phát ngay (nếu đang phát bài gợi ý) hoặc thêm vào cuối hàng đợi |
| MENU hoặc **giữ OK** trên một bài | Ưu tiên: đưa lên đầu hàng đợi |
| OK trên bài ở hàng đợi | Phát ngay bài đó |
| MENU hoặc giữ OK trên bài ở hàng đợi | Xoá khỏi hàng đợi |
| Giữ OK trên thể loại tự tạo | Xoá thể loại |
| Nút ⛶ ở góc màn hình phát | Phóng to toàn màn hình |
| **Bất kỳ phím nào** khi đang toàn màn hình | Thu nhỏ ngay lập tức |

## Tự phóng to
Sau **30 giây không bấm phím nào trên remote TV**, màn hình phát tự phóng to toàn màn hình.
Lệnh từ điện thoại (thêm bài, âm lượng, thả tim, tin nhắn…) **không** làm thu nhỏ.

## Tìm kiếm dùng chung TV ↔ điện thoại
- Tìm trên điện thoại → kết quả hiện luôn trên lưới của TV.
- Tìm trên TV (gõ/nói/chọn thể loại) → kết quả hiện luôn ở tab Tìm kiếm của remote điện thoại.
- Mặc định app là **tìm nghe nhạc** (không thêm chữ "karaoke"). Mỗi lần mở app, chế độ karaoke luôn **tắt**.

## Chế độ Karaoke (đồng bộ TV ↔ điện thoại)
- **TV**: nút **Karaoke** nằm cạnh ô tìm kiếm. Bật → nút sáng đỏ, mọi tìm kiếm trên TV (gõ, nói, chọn thể loại, tải thêm) tự thêm chữ *karaoke*; từ khoá đang hiện được tìm lại ngay.
- **Điện thoại**: ô tích **Chế độ karaoke** nằm trong menu ☰ và trong bảng nguồn/thể loại của remote.
- **Đồng bộ**: bật/tắt ở bên nào thì bên kia đổi theo (điện thoại cập nhật qua trạng thái TV mỗi giây; điện thoại đổi thì gọi `POST /api/karaoke {"on": true|false}`).

## Ghi chú kỹ thuật
- `applicationId` = `vn.savify.youtubeplayer` (cài song song được với app Savify Karaoke cũ).
- Danh sách bài tự phát khi hàng đợi trống: `app/src/main/assets/seed.json`.
- Thể loại tự tạo lưu trong SharedPreferences (`cats`).
- Khoá ký APK `karaoke.jks` (mật khẩu `karaoke123`) chỉ dùng cho cài nội bộ.
