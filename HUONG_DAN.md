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
| **INFO (ⓘ)**, hoặc nút **Vàng**, hoặc **CH+** | Phóng to video đang phát ngay lập tức (bấm lại để thu nhỏ) |
| **Bất kỳ phím nào** khi đang toàn màn hình | Thu nhỏ ngay lập tức |

## Mã QR, danh mục, lưới kết quả
- Nút **Ẩn/Hiện** nằm bên phải ô "CỔNG": ẩn thì mã QR biến mất hoàn toàn, danh mục âm nhạc trồi lên để thấy nhiều thể loại hơn (trạng thái được nhớ).
- Mọi thể loại (cả 5 thể loại mặc định) có nút **Xoá** ngay trên dòng; có thể khôi phục thể loại mặc định trong Cài đặt; bấm sẽ hiện hộp xác nhận giữa màn hình (mặc định chọn "GIỮ LẠI").
- *Cài đặt → Số bài mỗi hàng kết quả*: xoay vòng 3 → 2 → 1. Chế độ 1 bài/hàng hiển thị dạng danh sách (ảnh bên trái, tên bên phải).

## Tự phóng to
Mặc định sau **5 giây không bấm phím nào trên remote TV**, màn hình phát tự phóng to toàn màn hình.
Đổi số giây trong *Cài đặt → Tự phóng lớn video sau*: xoay vòng 3 → 5 → 10 → 15 → 30 → 60 giây → TẮT (không tự phóng lớn).
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
