package dbconn

import (
	"fmt"
)

// StringArray scans a Postgres text-format text/varchar array into a Go
// []string. It replaces pq.StringArray for callers that only need to read
// arrays out of the database; it does not implement driver.Valuer because no
// caller binds a StringArray as a query parameter.
//
// The element format follows the Postgres array external textual
// representation: elements are comma-separated, optionally wrapped in
// double quotes. Inside quotes, `\\` becomes `\` and `\"` becomes `"`.
// An unquoted `NULL` literal is rejected with an error (matching pq's strict
// behavior for StringArray); a quoted `"NULL"` is the string "NULL".
type StringArray []string

func (a *StringArray) Scan(src interface{}) error {
	switch v := src.(type) {
	case nil:
		*a = nil
		return nil
	case []byte:
		return a.scanBytes(v)
	case string:
		return a.scanBytes([]byte(v))
	}
	return fmt.Errorf("dbconn: cannot scan %T into StringArray", src)
}

func (a *StringArray) scanBytes(src []byte) error {
	if len(src) < 2 || src[0] != '{' || src[len(src)-1] != '}' {
		return fmt.Errorf("dbconn: malformed array literal %q", src)
	}
	body := src[1 : len(src)-1]
	if len(body) == 0 {
		*a = StringArray{}
		return nil
	}
	out := make(StringArray, 0)
	i := 0
	for i < len(body) {
		if body[i] == '"' {
			i++
			buf := make([]byte, 0, 16)
			for i < len(body) && body[i] != '"' {
				if body[i] == '\\' && i+1 < len(body) {
					buf = append(buf, body[i+1])
					i += 2
					continue
				}
				buf = append(buf, body[i])
				i++
			}
			if i >= len(body) {
				return fmt.Errorf("dbconn: unterminated quoted element in array %q", src)
			}
			i++ // consume closing quote
			out = append(out, string(buf))
		} else {
			start := i
			for i < len(body) && body[i] != ',' {
				i++
			}
			tok := body[start:i]
			if string(tok) == "NULL" {
				return fmt.Errorf("dbconn: cannot scan NULL element at index %d into StringArray", len(out))
			}
			out = append(out, string(tok))
		}
		if i < len(body) {
			if body[i] != ',' {
				return fmt.Errorf("dbconn: expected ',' between array elements in %q", src)
			}
			i++
		}
	}
	*a = out
	return nil
}
