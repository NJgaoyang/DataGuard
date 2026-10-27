-- Built-in safe SQL function registry. Admins can enable further vetted functions at runtime.
CREATE TABLE dg_sql_functions (
 function_name VARCHAR(64) NOT NULL PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
INSERT INTO dg_sql_functions (function_name) VALUES
 ('COUNT'),('SUM'),('AVG'),('MIN'),('MAX'),('ROUND'),('CEIL'),('CEILING'),
 ('FLOOR'),('ABS'),('MOD'),('POWER'),('SQRT'),('TRUNCATE'),('SIGN'),
 ('COALESCE'),('IFNULL'),('NULLIF'),('IF'),('GREATEST'),('LEAST'),
 ('DATE_FORMAT'),('DATE'),('YEAR'),('MONTH'),('DAY'),('DAYOFMONTH'),
 ('HOUR'),('MINUTE'),('SECOND'),('DATEDIFF'),('TIMESTAMPDIFF'),
 ('DATE_ADD'),('DATE_SUB'),('LAST_DAY'),
 ('CONCAT'),('CONCAT_WS'),('SUBSTRING'),('LEFT'),('RIGHT'),('LENGTH'),
 ('CHAR_LENGTH'),('LOWER'),('UPPER'),('TRIM'),('REPLACE'),('LPAD'),('RPAD');