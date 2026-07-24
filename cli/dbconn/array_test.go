package dbconn_test

import (
	"testing"

	"pxf-cli/dbconn"
)

func TestStringArrayScan(t *testing.T) {
	cases := []struct {
		name string
		src  interface{}
		want []string
		nil_ bool
	}{
		{"nil source", nil, nil, true},
		{"empty", "{}", []string{}, false},
		{"unquoted simple", "{a,b,c}", []string{"a", "b", "c"}, false},
		{"numeric", []byte("{1,2,3}"), []string{"1", "2", "3"}, false},
		{"quoted with comma", `{"a,b","c"}`, []string{"a,b", "c"}, false},
		{"quoted with escaped backslash", `{"a\\b"}`, []string{`a\b`}, false},
		{"quoted with escaped quote", `{"he said \"hi\""}`, []string{`he said "hi"`}, false},
		{"single element", `{x}`, []string{"x"}, false},
		{"mixed quoted and unquoted", `{a,"b,c",d}`, []string{"a", "b,c", "d"}, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var got dbconn.StringArray
			if err := got.Scan(tc.src); err != nil {
				t.Fatalf("Scan(%v): unexpected error %v", tc.src, err)
			}
			if tc.nil_ {
				if got != nil {
					t.Errorf("expected nil, got %v", got)
				}
				return
			}
			if len(got) != len(tc.want) {
				t.Fatalf("got %v (len=%d), want %v (len=%d)", got, len(got), tc.want, len(tc.want))
			}
			for i := range got {
				if got[i] != tc.want[i] {
					t.Errorf("element %d: got %q, want %q", i, got[i], tc.want[i])
				}
			}
		})
	}
}

func TestStringArrayScanErrors(t *testing.T) {
	cases := []struct {
		name string
		src  interface{}
	}{
		{"wrong type", 42},
		{"missing braces", "abc"},
		{"only opening brace", "{abc"},
		{"unterminated quote", `{"abc`},
		{"missing comma", `{"a""b"}`},
		{"unquoted NULL element rejected", `{NULL,a}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var got dbconn.StringArray
			if err := got.Scan(tc.src); err == nil {
				t.Errorf("Scan(%v): expected error, got nil (parsed as %v)", tc.src, got)
			}
		})
	}
}
