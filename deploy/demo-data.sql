-- 仅用于新建演示数据库；手动执行，不自动写入现有用户数据库。
-- 固定的雪花演示 ID（2026-09-01 UTC，预留节点 1023）便于重复初始化。
INSERT INTO course (id, name, edu, type, price, duration) VALUES
  (220331822288990209, 'Java 后端开发演示课程', 2, '编程', 3999, 90),
  (220331822288990210, 'Python 与 AI 应用演示课程', 2, '编程', 4999, 90),
  (220331822288990211, '数据分析入门演示课程', 1, '数据分析', 2999, 60)
ON DUPLICATE KEY UPDATE id = course.id;

INSERT INTO school (id, name, city) VALUES
  (220331822288990212, '上海演示校区', '上海'),
  (220331822288990213, '杭州演示校区', '杭州')
ON DUPLICATE KEY UPDATE id = school.id;
