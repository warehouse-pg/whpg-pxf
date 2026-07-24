package dbconn

import (
	"database/sql"
	"reflect"
	"strings"

	"errors"
)

/*
 * scanOne scans exactly one row from rows into dest. dest must be a non-nil
 * pointer. If dest points at a struct, columns are matched to struct fields
 * via "db" tags (falling back to lowercased field name). If dest points at a
 * scalar (or a sql.Scanner), the single column value is scanned directly.
 *
 * Returns sql.ErrNoRows if the query produced no rows, and ErrMultipleRows
 * if it produced more than one. dbconn.Get is meant for queries the caller
 * knows return a single row (catalog lookups by PK, scalar functions,
 * SHOW <guc>, etc.); pluralizing those is almost always a bug, so we surface
 * it instead of silently taking the first row.
 */
func scanOne(dest interface{}, rows *sql.Rows) error {
	if !rows.Next() {
		if err := rows.Err(); err != nil {
			return err
		}
		return sql.ErrNoRows
	}
	dv := reflect.ValueOf(dest)
	if dv.Kind() != reflect.Ptr || dv.IsNil() {
		return errors.New("destination must be a non-nil pointer")
	}
	elem := dv.Elem()
	var err error
	if elem.Kind() == reflect.Struct && !implementsScanner(dv.Type()) {
		err = scanRowIntoStruct(rows, elem)
	} else {
		err = rows.Scan(dest)
	}
	if err != nil {
		return err
	}
	if rows.Next() {
		return ErrMultipleRows
	}
	return rows.Err()
}

// ErrMultipleRows is returned by Get when a query produced more than one row.
var ErrMultipleRows = errors.New("dbconn.Get: query returned more than one row")

/*
 * scanAll scans every row into dest. dest must be a non-nil pointer to a
 * slice. The slice element type may be a struct (mapped via "db" tags) or a
 * scalar/sql.Scanner type for single-column queries.
 */
func scanAll(dest interface{}, rows *sql.Rows) error {
	dv := reflect.ValueOf(dest)
	if dv.Kind() != reflect.Ptr || dv.IsNil() {
		return errors.New("destination must be a non-nil pointer to a slice")
	}
	sliceVal := dv.Elem()
	if sliceVal.Kind() != reflect.Slice {
		return errors.New("destination must point to a slice")
	}
	elemType := sliceVal.Type().Elem()

	isPtr := elemType.Kind() == reflect.Ptr
	baseType := elemType
	if isPtr {
		baseType = elemType.Elem()
	}

	isStruct := baseType.Kind() == reflect.Struct && !implementsScanner(reflect.PointerTo(baseType))

	for rows.Next() {
		newElemPtr := reflect.New(baseType)
		if isStruct {
			if err := scanRowIntoStruct(rows, newElemPtr.Elem()); err != nil {
				return err
			}
		} else {
			if err := rows.Scan(newElemPtr.Interface()); err != nil {
				return err
			}
		}
		if isPtr {
			sliceVal.Set(reflect.Append(sliceVal, newElemPtr))
		} else {
			sliceVal.Set(reflect.Append(sliceVal, newElemPtr.Elem()))
		}
	}
	return rows.Err()
}

func scanRowIntoStruct(rows *sql.Rows, structVal reflect.Value) error {
	cols, err := rows.Columns()
	if err != nil {
		return err
	}
	fieldMap := buildFieldMap(structVal.Type())
	scanArgs := make([]interface{}, len(cols))
	var discard interface{}
	for i, col := range cols {
		idx, ok := fieldMap[col]
		if !ok {
			idx, ok = fieldMap[strings.ToLower(col)]
		}
		if !ok {
			scanArgs[i] = &discard
			continue
		}
		scanArgs[i] = structVal.FieldByIndex(idx).Addr().Interface()
	}
	return rows.Scan(scanArgs...)
}

/*
 * buildFieldMap walks a struct type (including embedded structs) and returns
 * a map from column name -> field index path. Column name is taken from a
 * `db:"..."` tag when present, otherwise the lowercased field name. Fields
 * tagged `db:"-"` are skipped.
 */
func buildFieldMap(t reflect.Type) map[string][]int {
	out := make(map[string][]int)
	var walk func(t reflect.Type, prefix []int)
	walk = func(t reflect.Type, prefix []int) {
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			idx := append(append([]int(nil), prefix...), i)
			if f.Anonymous && f.Type.Kind() == reflect.Struct {
				walk(f.Type, idx)
				continue
			}
			if !f.IsExported() {
				continue
			}
			tag := f.Tag.Get("db")
			if tag == "-" {
				continue
			}
			name := tag
			if name == "" {
				name = strings.ToLower(f.Name)
			}
			if _, exists := out[name]; !exists {
				out[name] = idx
			}
		}
	}
	walk(t, nil)
	return out
}

var scannerType = reflect.TypeOf((*sql.Scanner)(nil)).Elem()

func implementsScanner(t reflect.Type) bool {
	return t.Implements(scannerType)
}
