package operating

import (
	"io"
	"os"
	"os/user"
	"path/filepath"
	"time"
)

var (
	System = InitializeSystemFunctions()
)

type ReadCloserAt interface {
	io.ReadCloser
	io.ReaderAt
}

func OpenFileRead(name string, flag int, perm os.FileMode) (ReadCloserAt, error) {
	var reader ReadCloserAt
	var err error
	reader, err = os.OpenFile(name, flag, perm)
	return reader, err
}

func OpenFileWrite(name string, flag int, perm os.FileMode) (io.WriteCloser, error) {
	var writer io.WriteCloser
	var err error
	writer, err = os.OpenFile(name, flag, perm)
	return writer, err
}

type SystemFunctions struct {
	Chmod         func(name string, mode os.FileMode) error
	CurrentUser   func() (*user.User, error)
	Exit          func(code int)
	Getenv        func(key string) string
	Getpid        func() int
	Glob          func(pattern string) (matches []string, err error)
	Hostname      func() (string, error)
	IsNotExist    func(err error) bool
	LookupEnv     func(key string) (string, bool)
	MkdirAll      func(path string, perm os.FileMode) error
	Now           func() time.Time
	OpenFileRead  func(name string, flag int, perm os.FileMode) (ReadCloserAt, error)
	OpenFileWrite func(name string, flag int, perm os.FileMode) (io.WriteCloser, error)
	ReadFile      func(filename string) ([]byte, error)
	Remove        func(name string) error
	RemoveAll     func(name string) error
	Stat          func(name string) (os.FileInfo, error)
	Stdin         ReadCloserAt
	Stdout        io.WriteCloser
	TempFile      func(dir, pattern string) (f *os.File, err error)
	Local         *time.Location
}

func InitializeSystemFunctions() *SystemFunctions {
	return &SystemFunctions{
		Chmod:         os.Chmod,
		CurrentUser:   user.Current,
		Exit:          os.Exit,
		Getenv:        os.Getenv,
		Getpid:        os.Getpid,
		Glob:          filepath.Glob,
		Hostname:      os.Hostname,
		IsNotExist:    os.IsNotExist,
		MkdirAll:      os.MkdirAll,
		LookupEnv:     os.LookupEnv,
		Now:           time.Now,
		OpenFileRead:  OpenFileRead,
		OpenFileWrite: OpenFileWrite,
		ReadFile:      os.ReadFile,
		Remove:        os.Remove,
		RemoveAll:     os.RemoveAll,
		Stat:          os.Stat,
		Stdin:         os.Stdin,
		Stdout:        os.Stdout,
		TempFile:      os.CreateTemp,
		Local:         time.Local,
	}
}
