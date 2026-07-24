package dbconn

import (
	"database/sql"
	"errors"
	"reflect"
	"testing"

	sqlmock "github.com/DATA-DOG/go-sqlmock"
)

func openMock(t *testing.T) (*sql.DB, sqlmock.Sqlmock) {
	t.Helper()
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db, mock
}

func query(t *testing.T, db *sql.DB) *sql.Rows {
	t.Helper()
	rows, err := db.Query("SELECT *")
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	return rows
}

// db: tag mapping wins over field name.
func TestScanAll_DBTagMapping(t *testing.T) {
	type Row struct {
		ID   int    `db:"row_id"`
		Name string `db:"row_name"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"row_id", "row_name"}).
			AddRow(1, "alice").
			AddRow(2, "bob"),
	)

	var got []Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	want := []Row{{1, "alice"}, {2, "bob"}}
	if len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Errorf("got %+v want %+v", got, want)
	}
}

// Missing db: tag falls back to lowercased field name.
func TestScanAll_LowercasedFieldName(t *testing.T) {
	type Row struct {
		Oid  uint32
		Name string
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"oid", "name"}).AddRow(uint32(42), "x"),
	)
	var got []Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 1 || got[0].Oid != 42 || got[0].Name != "x" {
		t.Errorf("got %+v", got)
	}
}

// Embedded struct fields are reachable via the same flat column space.
func TestScanAll_EmbeddedStruct(t *testing.T) {
	type Base struct {
		ID int `db:"id"`
	}
	type Row struct {
		Base
		Name string `db:"name"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"id", "name"}).AddRow(7, "deep"),
	)
	var got []Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 1 || got[0].ID != 7 || got[0].Name != "deep" {
		t.Errorf("got %+v", got)
	}
}

// Unknown columns are scanned into a discard placeholder, not an error.
func TestScanAll_UnknownColumnIgnored(t *testing.T) {
	type Row struct {
		ID int `db:"id"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"id", "extra"}).AddRow(1, "ignored"),
	)
	var got []Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 1 || got[0].ID != 1 {
		t.Errorf("got %+v", got)
	}
}

// Fields tagged db:"-" are skipped entirely.
func TestScanAll_SkipDashTag(t *testing.T) {
	type Row struct {
		ID  int `db:"id"`
		Sec int `db:"-"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"id"}).AddRow(99),
	)
	var got []Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 1 || got[0].ID != 99 || got[0].Sec != 0 {
		t.Errorf("got %+v", got)
	}
}

// StringArray implements sql.Scanner; scanRowIntoStruct must hand the column
// value to that Scanner via rows.Scan rather than reflecting into it.
func TestScanAll_SQLScannerField(t *testing.T) {
	type Row struct {
		ID   int         `db:"id"`
		Tags StringArray `db:"tags"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"id", "tags"}).AddRow(1, "{a,b,c}"),
	)
	var got []Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 1 {
		t.Fatalf("rows: %+v", got)
	}
	if got[0].ID != 1 || len(got[0].Tags) != 3 || got[0].Tags[0] != "a" {
		t.Errorf("got %+v", got)
	}
}

// sql.NullString fields work as a special case of sql.Scanner.
func TestScanAll_NullableField(t *testing.T) {
	type Row struct {
		Name sql.NullString `db:"name"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"name"}).AddRow(nil).AddRow("set"),
	)
	var got []Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 2 || got[0].Name.Valid || !got[1].Name.Valid || got[1].Name.String != "set" {
		t.Errorf("got %+v", got)
	}
}

// Slice of pointer to struct also works.
func TestScanAll_SliceOfPointer(t *testing.T) {
	type Row struct {
		ID int `db:"id"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"id"}).AddRow(1).AddRow(2),
	)
	var got []*Row
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 2 || got[0].ID != 1 || got[1].ID != 2 {
		t.Errorf("got %+v", got)
	}
}

// Scalar destinations skip the reflection path entirely.
func TestScanAll_ScalarSlice(t *testing.T) {
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"v"}).AddRow("a").AddRow("b").AddRow("c"),
	)
	var got []string
	if err := scanAll(&got, query(t, db)); err != nil {
		t.Fatalf("scanAll: %v", err)
	}
	if len(got) != 3 || got[0] != "a" || got[2] != "c" {
		t.Errorf("got %+v", got)
	}
}

// scanOne returns sql.ErrNoRows on empty result sets.
func TestScanOne_NoRowsErr(t *testing.T) {
	type Row struct {
		ID int `db:"id"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(sqlmock.NewRows([]string{"id"}))
	var got Row
	err := scanOne(&got, query(t, db))
	if !errors.Is(err, sql.ErrNoRows) {
		t.Errorf("want sql.ErrNoRows, got %v", err)
	}
}

// scanOne errors when the query returned more than one row. Get is for
// single-row lookups; surfacing the plurality beats silently dropping data.
func TestScanOne_ErrorsOnExtraRows(t *testing.T) {
	type Row struct {
		ID int `db:"id"`
	}
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"id"}).AddRow(1).AddRow(2),
	)
	var got Row
	err := scanOne(&got, query(t, db))
	if !errors.Is(err, ErrMultipleRows) {
		t.Errorf("want ErrMultipleRows, got %v", err)
	}
}

// scanOne handles scalar destinations.
func TestScanOne_Scalar(t *testing.T) {
	db, mock := openMock(t)
	mock.ExpectQuery("SELECT").WillReturnRows(
		sqlmock.NewRows([]string{"n"}).AddRow(42),
	)
	var n int
	if err := scanOne(&n, query(t, db)); err != nil {
		t.Fatalf("scanOne: %v", err)
	}
	if n != 42 {
		t.Errorf("want 42 got %d", n)
	}
}

// buildFieldMap exact-tag wins over a competing lowercased field name.
func TestBuildFieldMap_TagWinsOverFieldName(t *testing.T) {
	type Row struct {
		Foo string `db:"bar"`
	}
	m := buildFieldMap(reflect.TypeOf(Row{}))
	if _, ok := m["bar"]; !ok {
		t.Errorf("expected tag-named entry 'bar' in %+v", m)
	}
	if _, ok := m["foo"]; ok {
		t.Errorf("did not expect fallback entry 'foo' when tag set: %+v", m)
	}
}
