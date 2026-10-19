-- Schema catalog: the shared Skill Categories and Skills (FR-72, AD-38). Owned by the migration
-- role; the runtime role ${runtime_role} only reads until the Administrator catalog story (ADM-12)
-- grants the writes admin needs. Nothing here is deleted: a retired entry is set inactive.

CREATE SCHEMA catalog;

GRANT USAGE ON SCHEMA catalog TO ${runtime_role};

-- A Skill Category, shown in the Administrator's display order.
CREATE TABLE catalog.skill_category
(
    id            UUID PRIMARY KEY,
    name          TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60 AND name = btrim(name)),
    display_order INTEGER     NOT NULL CHECK (display_order >= 0),
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);

-- FR-72 uniqueness, under the SG-50 folding: "react" beside "React", or "Bao mat" beside "Bảo mật",
-- is the same name.
CREATE UNIQUE INDEX skill_category_name_idx ON catalog.skill_category (platform.vi_normalize(name));

-- A Skill: its plain hint for Customer screens (NFR-12), and the match keys FR-9 suggests it by
-- (GitHub language names, and the AI tools of FR-7).
CREATE TABLE catalog.skill
(
    id              UUID PRIMARY KEY,
    category_id     UUID        NOT NULL REFERENCES catalog.skill_category (id),
    name            TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60 AND name = btrim(name)),
    hint            TEXT        NOT NULL CHECK (char_length(hint) BETWEEN 1 AND 160),
    match_languages TEXT[]      NOT NULL DEFAULT '{}',
    match_tools     TEXT[]      NOT NULL DEFAULT '{}',
    display_order   INTEGER     NOT NULL CHECK (display_order >= 0),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX skill_name_idx ON catalog.skill (platform.vi_normalize(name));
CREATE INDEX skill_category_idx ON catalog.skill (category_id, display_order);

GRANT SELECT ON catalog.skill_category, catalog.skill TO ${runtime_role};

-- The seed: PRD addendum B7, 8 Categories and 61 Skills, with fixed IDs. Category names are the UI
-- names; technology names stay as their makers write them, and descriptive Skills are Vietnamese.

INSERT INTO catalog.skill_category (id, name, display_order, created_at, updated_at)
VALUES
    ('0192b000-0000-7000-8000-000000000001', 'Giao diện web', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8000-000000000002', 'Máy chủ và API', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8000-000000000003', 'Dữ liệu và dịch vụ dữ liệu', 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8000-000000000004', 'Ứng dụng di động', 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8000-000000000005', 'Triển khai và vận hành', 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8000-000000000006', 'Bảo mật và kiểm thử', 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8000-000000000007', 'Tích hợp và thanh toán', 70, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8000-000000000008', 'Công cụ tạo ứng dụng bằng AI', 80, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00');

INSERT INTO catalog.skill (id, category_id, name, hint, match_languages, match_tools, display_order, created_at, updated_at)
VALUES
    ('0192b000-0000-7000-8001-000000000001', '0192b000-0000-7000-8000-000000000001', 'HTML & CSS',
     'Dựng khung và cách trình bày của trang web.', ARRAY['HTML', 'CSS'], '{}', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000002', '0192b000-0000-7000-8000-000000000001', 'JavaScript',
     'Ngôn ngữ chạy trong trình duyệt, giúp trang web phản hồi thao tác.', ARRAY['JavaScript'], '{}', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000003', '0192b000-0000-7000-8000-000000000001', 'TypeScript',
     'JavaScript có kiểm tra kiểu dữ liệu, giúp bắt lỗi sớm.', ARRAY['TypeScript'], ARRAY['Lovable'], 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000004', '0192b000-0000-7000-8000-000000000001', 'React',
     'Thư viện dựng giao diện web từ các phần nhỏ dùng lại được.', '{}', ARRAY['Lovable', 'v0', 'Bolt.new'], 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000005', '0192b000-0000-7000-8000-000000000001', 'Next.js',
     'Khung làm web dựa trên React, dựng sẵn trang ở máy chủ.', '{}', ARRAY['v0'], 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000006', '0192b000-0000-7000-8000-000000000001', 'Vue.js',
     'Khung dựng giao diện web gọn và dễ học.', ARRAY['Vue'], '{}', 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000007', '0192b000-0000-7000-8000-000000000001', 'Tailwind CSS',
     'Cách viết giao diện bằng các lớp trình bày có sẵn.', '{}', ARRAY['Lovable', 'v0'], 70, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000008', '0192b000-0000-7000-8000-000000000001', 'Vite',
     'Công cụ chạy thử và đóng gói ứng dụng web nhanh.', '{}', ARRAY['Lovable', 'Bolt.new'], 80, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000009', '0192b000-0000-7000-8000-000000000002', 'Node.js',
     'Chạy JavaScript ở máy chủ.', '{}', '{}', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000010', '0192b000-0000-7000-8000-000000000002', 'Express',
     'Khung nhỏ để làm máy chủ web bằng Node.js.', '{}', '{}', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000011', '0192b000-0000-7000-8000-000000000002', 'NestJS',
     'Khung có cấu trúc rõ ràng để làm máy chủ bằng TypeScript.', '{}', '{}', 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000012', '0192b000-0000-7000-8000-000000000002', 'Python',
     'Ngôn ngữ phổ biến cho máy chủ, dữ liệu và tự động hóa.', ARRAY['Python'], '{}', 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000013', '0192b000-0000-7000-8000-000000000002', 'Django',
     'Khung làm web đầy đủ bằng Python.', '{}', '{}', 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000014', '0192b000-0000-7000-8000-000000000002', 'FastAPI',
     'Khung làm API nhanh bằng Python.', '{}', '{}', 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000015', '0192b000-0000-7000-8000-000000000002', 'Java',
     'Ngôn ngữ cho máy chủ lớn và ứng dụng doanh nghiệp.', ARRAY['Java'], '{}', 70, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000016', '0192b000-0000-7000-8000-000000000002', 'Spring Boot',
     'Khung làm máy chủ bằng Java.', '{}', '{}', 80, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000017', '0192b000-0000-7000-8000-000000000002', 'PHP',
     'Ngôn ngữ làm web chạy được trên hầu hết dịch vụ thuê máy chủ.', ARRAY['PHP'], '{}', 90, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000018', '0192b000-0000-7000-8000-000000000002', 'Laravel',
     'Khung làm web bằng PHP.', '{}', '{}', 100, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000019', '0192b000-0000-7000-8000-000000000002', 'Go',
     'Ngôn ngữ gọn và nhanh cho máy chủ và công cụ.', ARRAY['Go'], '{}', 110, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000020', '0192b000-0000-7000-8000-000000000002', 'REST API',
     'Cách các ứng dụng trao đổi dữ liệu qua địa chỉ web.', '{}', '{}', 120, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000021', '0192b000-0000-7000-8000-000000000002', 'GraphQL',
     'Cách lấy đúng phần dữ liệu cần từ máy chủ trong một lần hỏi.', '{}', '{}', 130, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000022', '0192b000-0000-7000-8000-000000000003', 'PostgreSQL',
     'Cơ sở dữ liệu dạng bảng, mạnh và miễn phí.', ARRAY['PLpgSQL'], '{}', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000023', '0192b000-0000-7000-8000-000000000003', 'MySQL',
     'Cơ sở dữ liệu dạng bảng phổ biến cho web.', '{}', '{}', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000024', '0192b000-0000-7000-8000-000000000003', 'MongoDB',
     'Cơ sở dữ liệu lưu dạng tài liệu, cấu trúc linh hoạt.', '{}', '{}', 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000025', '0192b000-0000-7000-8000-000000000003', 'Supabase',
     'Dịch vụ có sẵn cơ sở dữ liệu, đăng nhập và lưu tệp.', '{}', ARRAY['Lovable'], 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000026', '0192b000-0000-7000-8000-000000000003', 'Firebase',
     'Dịch vụ của Google cho dữ liệu, đăng nhập và thông báo.', '{}', '{}', 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000027', '0192b000-0000-7000-8000-000000000003', 'Prisma',
     'Công cụ đọc và ghi cơ sở dữ liệu từ mã TypeScript.', '{}', '{}', 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000028', '0192b000-0000-7000-8000-000000000003', 'Redis',
     'Kho dữ liệu trong bộ nhớ, dùng làm bộ đệm và hàng đợi.', '{}', '{}', 70, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000029', '0192b000-0000-7000-8000-000000000004', 'React Native',
     'Làm ứng dụng điện thoại bằng React.', '{}', '{}', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000030', '0192b000-0000-7000-8000-000000000004', 'Flutter',
     'Làm ứng dụng cho cả Android và iPhone từ một mã nguồn.', ARRAY['Dart'], '{}', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000031', '0192b000-0000-7000-8000-000000000004', 'Expo',
     'Bộ công cụ chạy thử và phát hành ứng dụng React Native.', '{}', '{}', 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000032', '0192b000-0000-7000-8000-000000000004', 'Kotlin',
     'Ngôn ngữ làm ứng dụng Android.', ARRAY['Kotlin'], '{}', 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000033', '0192b000-0000-7000-8000-000000000004', 'Swift',
     'Ngôn ngữ làm ứng dụng iPhone.', ARRAY['Swift'], '{}', 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000034', '0192b000-0000-7000-8000-000000000005', 'Docker',
     'Đóng gói ứng dụng để chạy giống nhau trên mọi máy.', ARRAY['Dockerfile'], '{}', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000035', '0192b000-0000-7000-8000-000000000005', 'Vercel',
     'Dịch vụ đưa trang web lên mạng nhanh.', '{}', '{}', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000036', '0192b000-0000-7000-8000-000000000005', 'Netlify',
     'Dịch vụ đưa trang web tĩnh và hàm nhỏ lên mạng.', '{}', '{}', 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000037', '0192b000-0000-7000-8000-000000000005', 'Render',
     'Dịch vụ chạy máy chủ và cơ sở dữ liệu trên mạng.', '{}', '{}', 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000038', '0192b000-0000-7000-8000-000000000005', 'Cloudflare',
     'Dịch vụ tên miền, tăng tốc và chặn tấn công cho trang web.', '{}', '{}', 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000039', '0192b000-0000-7000-8000-000000000005', 'Máy chủ Linux',
     'Cài đặt và chăm sóc máy chủ chạy Linux.', '{}', '{}', 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000040', '0192b000-0000-7000-8000-000000000005', 'Tên miền và DNS',
     'Trỏ tên miền về đúng nơi chạy trang web.', '{}', '{}', 70, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000041', '0192b000-0000-7000-8000-000000000006', 'Đăng nhập và phân quyền',
     'Ai được vào và được làm gì trong ứng dụng.', '{}', '{}', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000042', '0192b000-0000-7000-8000-000000000006', 'Supabase Row Level Security',
     'Luật cho mỗi người chỉ đọc đúng dữ liệu của mình trong Supabase.', '{}', '{}', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000043', '0192b000-0000-7000-8000-000000000006', 'Bảo mật web (OWASP Top 10)',
     'Chặn những lỗi bảo mật web thường gặp nhất.', '{}', '{}', 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000044', '0192b000-0000-7000-8000-000000000006', 'Quản lý khóa bí mật',
     'Giữ mật khẩu và khóa truy cập dịch vụ ngoài mã nguồn.', '{}', '{}', 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000045', '0192b000-0000-7000-8000-000000000006', 'Kiểm thử đơn vị',
     'Viết mã tự kiểm tra từng phần nhỏ của ứng dụng.', '{}', '{}', 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000046', '0192b000-0000-7000-8000-000000000006', 'Kiểm thử đầu cuối',
     'Tự động bấm thử ứng dụng như người dùng thật.', '{}', '{}', 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000047', '0192b000-0000-7000-8000-000000000007', 'VNPay',
     'Nhận thanh toán qua cổng VNPay.', '{}', '{}', 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000048', '0192b000-0000-7000-8000-000000000007', 'MoMo',
     'Nhận thanh toán qua ví MoMo.', '{}', '{}', 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000049', '0192b000-0000-7000-8000-000000000007', 'Stripe',
     'Nhận thanh toán bằng thẻ quốc tế qua Stripe.', '{}', '{}', 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000050', '0192b000-0000-7000-8000-000000000007', 'Gửi email',
     'Gửi email xác nhận, nhắc lịch và thông báo từ ứng dụng.', '{}', '{}', 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000051', '0192b000-0000-7000-8000-000000000007', 'Google Maps',
     'Hiện bản đồ và tìm địa chỉ trong ứng dụng.', '{}', '{}', 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000052', '0192b000-0000-7000-8000-000000000007', 'Đăng nhập bằng mạng xã hội',
     'Cho người dùng đăng nhập bằng Google, Facebook hoặc Zalo.', '{}', '{}', 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000053', '0192b000-0000-7000-8000-000000000007', 'API AI (OpenAI, Gemini)',
     'Gắn tính năng AI vào ứng dụng qua dịch vụ của OpenAI hoặc Google.', '{}', '{}', 70, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000054', '0192b000-0000-7000-8000-000000000008', 'Lovable',
     'Tạo ứng dụng web bằng cách mô tả cho AI.', '{}', ARRAY['Lovable'], 10, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000055', '0192b000-0000-7000-8000-000000000008', 'Bolt.new',
     'Tạo và chạy ứng dụng web ngay trên trình duyệt bằng AI.', '{}', ARRAY['Bolt.new'], 20, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000056', '0192b000-0000-7000-8000-000000000008', 'Replit',
     'Viết, chạy và đưa ứng dụng lên mạng trên trình duyệt, có AI hỗ trợ.', '{}', ARRAY['Replit'], 30, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000057', '0192b000-0000-7000-8000-000000000008', 'v0',
     'Tạo giao diện React bằng cách mô tả cho AI.', '{}', ARRAY['v0'], 40, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000058', '0192b000-0000-7000-8000-000000000008', 'Base44',
     'Tạo ứng dụng có sẵn dữ liệu và đăng nhập bằng AI.', '{}', ARRAY['Base44'], 50, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000059', '0192b000-0000-7000-8000-000000000008', 'Cursor',
     'Trình soạn mã có AI viết và sửa mã.', '{}', ARRAY['Cursor'], 60, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000060', '0192b000-0000-7000-8000-000000000008', 'Claude Code',
     'AI viết và sửa mã ngay trong thư mục dự án.', '{}', ARRAY['Claude Code'], 70, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00'),
    ('0192b000-0000-7000-8001-000000000061', '0192b000-0000-7000-8000-000000000008', 'Codex',
     'AI của OpenAI viết và sửa mã theo yêu cầu.', '{}', ARRAY['Codex'], 80, TIMESTAMPTZ '2026-10-19 00:00:00+00', TIMESTAMPTZ '2026-10-19 00:00:00+00');
