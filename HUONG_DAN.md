# YOUTUBE PLAYER cho Android TV

Ứng dụng nghe nhạc YouTube trên TV, điều khiển bằng điện thoại (quét mã QR) hoặc bằng remote TV.
Dựng từ nền kỹ thuật của app Savify Karaoke (NewPipe Extractor + ExoPlayer + server NanoHTTPD + QR).
Cách build/cài đặt giống hệt app karaoke: đưa toàn bộ thư mục lên GitHub → Actions → **Build APK** → tải `youtube-player.apk`.

## Giao diện TV (bố cục mới)
- **Thanh trên cùng**: logo + tên app, trạng thái remote, 3 nút tròn **Yêu thích** (hồng), **Nhạc trong máy** (xanh lá), **Tải sẵn về máy** (xanh ngọc, hiện số bài đang tải); bên phải: nút **QR** (mở hộp mã QR kết nối remote), nút **Cài đặt**, nút **Sáng/Tối** (mặt trời/mặt trăng, ngay bên phải nút Cài đặt; bấm để đổi giao diện sáng ↔ tối, được nhớ lại), Wi-Fi, đồng hồ. **Bấm vào logo** (góc trái) từ Yêu thích / danh sách phát để quay về trang tìm kiếm cũ.
- **Hàng tìm kiếm**: micro, ô tìm + nút **TÌM** + nút **Danh Mục ▾** (mở danh sách thể loại; giữ OK hoặc nút Xoá để xoá thể loại, có nút tạo thể loại mới), nút **Karaoke**.
- **Trái**: lưới kết quả (mặc định 2 bài/hàng; Cài đặt → "Số bài mỗi hàng kết quả" xoay vòng 1 → 2 → 3 → 4). Mỗi bài có nút tải xuống ở góc ảnh.
- **Giữa**: **HÀNG ĐỢI TIẾP THEO** — mỗi bài có 4 nút: Phát ngay, Lên, Xuống, Xoá; chân khung có tổng thời gian và nút **Dán Link** (thêm bài từ link YouTube).
- **Phải**: **ĐANG PHÁT** — màn hình nhỏ (nhãn độ phân giải, nhãn ĐANG PHÁT/ĐANG TẠM DỪNG, nút phóng to), tiêu đề, kênh, thanh tiến trình, 6 nút (lùi 10s, phát/dừng, bài tiếp, tua 10s, **phát lại từ đầu**, **lặp lại**), thanh trượt âm lượng.

## Chức năng mới
- **Lặp lại** (nút cuối hàng điều khiển): bấm xoay vòng TẮT → lặp 1 bài → lặp cả hàng đợi.
- **Nhạc & video trong máy** (nút xanh lá): *Chọn & phát ngay…*, *+ Thêm file…*, *+ Thêm thư mục…* (chọn bằng bộ chọn file của Android; nếu TV không có bộ chọn sẽ báo "TV này chưa hỗ trợ"). Phát mp3, m4a, wav, flac, ogg, opus, aac, mp4, mkv, webm, mov, m4v. Có ô lọc, nút *Phát ngay* / *+ Hàng đợi* cho từng bài và *Thêm tất cả vào hàng đợi*. Thư mục đã thêm hiện thành thẻ, bấm ✕ để bỏ.
- **Tải sẵn về máy** (nút xanh ngọc): chọn *Kiểu tải* (Hình + tiếng / Chỉ tiếng m4a); *Phạm vi tải*: Yêu thích hoặc Hàng đợi hiện tại → *Tải về*; danh sách bài đã tải có lọc, *Phát ngay*, *+ Hàng đợi*, *Xoá*; mục *Tiến độ* có *Huỷ tất cả* và *Xoá dòng đã xong*. Bài đã tải phát thẳng từ file, không cần mạng. Cũng có thể bấm biểu tượng tải trên từng bài hoặc phím **Xanh dương** trên remote. File lưu trong `Android/data/vn.savify.youtubeplayer/files/Music/YoutubePlayer` (Android không cho đổi/mở thư mục này từ app). Chỉ dùng cho mục đích cá nhân, tôn trọng bản quyền.
- **Dán Link**: dán link YouTube (tự lấy từ clipboard nếu có) để thêm bài vào hàng đợi.
- Đã bỏ khỏi bản này: nút LED, hình ảnh chờ, điều khiển LED/thiết bị (theo ảnh mẫu).

## Phím remote TV
| Phím | Tác dụng |
|---|---|
| OK trên một bài | Phát ngay (nếu đang phát bài gợi ý) hoặc thêm vào cuối hàng đợi |
| MENU hoặc **giữ OK** trên một bài | Ưu tiên: đưa lên đầu hàng đợi |
| OK trên bài ở hàng đợi | Phát ngay bài đó |
| OK trên nút của bài ở hàng đợi | Phát / Lên / Xuống / Xoá; MENU = xoá khỏi hàng đợi |
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

## Tìm kiếm giống YouTube
- Kết quả gồm **video (cả MV) và danh sách phát (playlist)**, thứ tự theo YouTube; cuộn/di chuyển tới cuối là **tự tải tiếp, không giới hạn** (cả trên TV lẫn remote điện thoại).
- Playlist có nhãn **DANH SÁCH PHÁT** và số video. **OK** trên playlist = mở xem các video bên trong (cũng cuộn tải thêm không giới hạn), **BACK** để quay lại kết quả trước. **Giữ OK** hoặc **MENU** trên playlist = thêm tối đa 100 video của playlist vào hàng đợi.
- Điện thoại: bấm một playlist để mở, nút **+** của playlist = thêm cả danh sách vào hàng đợi.

## Âm lượng
Âm lượng của app chính là âm lượng hệ thống của TV/box: phím VOL+/VOL− trên remote TV và nút +/− hoặc thanh trượt trên điện thoại luôn đồng bộ với nhau.

## Ghi chú kỹ thuật
- `applicationId` = `vn.savify.youtubeplayer` (cài song song được với app Savify Karaoke cũ).
- Danh sách bài tự phát khi hàng đợi trống: `app/src/main/assets/seed.json`.
- Thể loại tự tạo lưu trong SharedPreferences (`cats`).
- Khoá ký APK `karaoke.jks` (mật khẩu `karaoke123`) chỉ dùng cho cài nội bộ.
