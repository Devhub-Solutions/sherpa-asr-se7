# Java JNA Project

## Mô tả
Dự án Java mẫu sử dụng JNA (Java Native Access) - tương thích JDK 7.

## Cấu trúc
- `src/main/java/com/example/jna/` - Source code chính
  - `CLibrary.java` - Interface gọi hàm C chuẩn (printf, strlen, atoi, getenv, malloc/free)
  - `TimeLibrary.java` - Interface gọi hàm time/getpid
  - `TmStructure.java` - Ví dụ JNA Structure (map struct C)
  - `Kernel32Util.java` - Tiện ích lấy thông tin hệ thống qua JNA
  - `JNAMain.java` - Lớp chính (main class)
- `src/test/java/com/example/jna/` - Unit tests
- `lib/` - Dependencies JAR (sao chép sau khi build)

## Yêu cầu
- JDK 7+ (biên dịch với source/target = 1.7)
- Maven 3.x

## Build
```bash
mvn clean package
```

## Chạy
```bash
java -jar target/java-jna-project-1.0.0-all.jar
```

## Import vào Eclipse
1. File → Import → General → Existing Projects into Workspace
2. Chọn thư mục project
3. Hoặc: File → Import → Maven → Existing Maven Projects

## Output sau build
- `target/java-jna-project-1.0.0.jar` - JAR chính (không含 dependencies)
- `target/java-jna-project-1.0.0-all.jar` - Fat JAR (含 tất cả dependencies)
- `target/lib/` - Thư mục chứa các dependency JAR