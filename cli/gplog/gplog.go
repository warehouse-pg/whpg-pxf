package gplog

import (
	"fmt"
	"io"
	"log"
	"os"
	"runtime"
	"strings"
	"sync"

	"pxf-cli/operating"
)

var (
	/*
	 * Error code key:
	 *   0: Completed successfully (default value)
	 *   1: Completed, but encountered a non-fatal error (set by logger.Error)
	 *   2: Did not complete, encountered a fatal error (set by logger.Fatal)
	 */
	errorCode = 0
	logger    *GpLogger
	logMutex  sync.Mutex
)

const (
	LOGERROR = iota
	LOGINFO
	LOGVERBOSE
	LOGDEBUG
)

type LogPrefixFunc func(string) string

type GpLogger struct {
	logStdout      *log.Logger
	logStderr      *log.Logger
	logFile        *log.Logger
	logFileName    string
	shellVerbosity int
	fileVerbosity  int
	header         string
	logPrefixFunc  LogPrefixFunc
}

func InitializeLogging(program string, logdir string) {
	if logger != nil {
		return
	}
	currentUser, _ := operating.System.CurrentUser()
	if logdir == "" {
		logdir = fmt.Sprintf("%s/gpAdminLogs", currentUser.HomeDir)
	}

	createLogDirectory(logdir)

	logfile := GenerateLogFileName(program, logdir)
	logFileHandle := openLogFile(logfile)

	logger = NewLogger(os.Stdout, os.Stderr, logFileHandle, logfile, LOGINFO, program)
}

func GenerateLogFileName(program, logdir string) string {
	timestamp := operating.System.Now().Format("20060102")
	return fmt.Sprintf("%s/%s_%s.log", logdir, program, timestamp)
}

func SetLogger(log *GpLogger) {
	logger = log
}

// GetLogger should only be used for testing purposes.
func GetLogger() *GpLogger {
	return logger
}

func NewLogger(stdout io.Writer, stderr io.Writer, logFile io.Writer, logFileName string, shellVerbosity int, program string, logFileVerbosity ...int) *GpLogger {
	fileVerbosity := LOGDEBUG
	if len(logFileVerbosity) == 1 && logFileVerbosity[0] >= LOGERROR && logFileVerbosity[0] <= LOGDEBUG {
		fileVerbosity = logFileVerbosity[0]
	}
	return &GpLogger{
		logStdout:      log.New(stdout, "", 0),
		logStderr:      log.New(stderr, "", 0),
		logFile:        log.New(logFile, "", 0),
		logFileName:    logFileName,
		shellVerbosity: shellVerbosity,
		fileVerbosity:  fileVerbosity,
		header:         GetHeader(program),
	}
}

func GetHeader(program string) string {
	headerFormatStr := "%s:%s:%s:%06d-[%s]:-"
	currentUser, _ := operating.System.CurrentUser()
	user := currentUser.Username
	host, _ := operating.System.Hostname()
	pid := operating.System.Getpid()
	return fmt.Sprintf(headerFormatStr, program, user, host, pid, "%s")
}

func defaultLogPrefixFunc(level string) string {
	logTimestamp := operating.System.Now().Format("20060102:15:04:05")
	return fmt.Sprintf("%s %s", logTimestamp, fmt.Sprintf(logger.header, level))
}

// SetLogPrefixFunc overrides the prefix prepended to every log line. Passing a
// function that returns "" suppresses the timestamp/header prefix entirely.
func SetLogPrefixFunc(logPrefixFunc func(string) string) {
	logger.logPrefixFunc = logPrefixFunc
}

func GetLogPrefix(level string) string {
	if logger.logPrefixFunc != nil {
		return logger.logPrefixFunc(level)
	}
	return defaultLogPrefixFunc(level)
}

func GetShellLogPrefix(level string) string {
	return GetLogPrefix(level)
}

func GetLogFilePath() string {
	return logger.logFileName
}

func GetVerbosity() int {
	return logger.shellVerbosity
}

func SetVerbosity(verbosity int) {
	logger.shellVerbosity = verbosity
}

func GetLogFileVerbosity() int {
	return logger.fileVerbosity
}

func SetLogFileVerbosity(verbosity int) {
	logger.fileVerbosity = verbosity
}

func GetErrorCode() int {
	return errorCode
}

func SetErrorCode(code int) {
	errorCode = code
}

func getVerbosityString(verbosity int) string {
	switch verbosity {
	case LOGERROR:
		return "ERROR"
	case LOGINFO:
		return "INFO"
	case LOGVERBOSE:
		return "DEBUG"
	case LOGDEBUG:
		return "DEBUG"
	}
	return ""
}

func Info(s string, v ...interface{}) {
	logMutex.Lock()
	defer logMutex.Unlock()
	if logger.fileVerbosity >= LOGINFO {
		message := GetLogPrefix("INFO") + fmt.Sprintf(s, v...)
		_ = logger.logFile.Output(1, message)
	}
	if logger.shellVerbosity >= LOGINFO {
		message := GetShellLogPrefix("INFO") + fmt.Sprintf(s, v...)
		_ = logger.logStdout.Output(1, message)
	}
}

func Warn(s string, v ...interface{}) {
	logMutex.Lock()
	defer logMutex.Unlock()
	message := GetLogPrefix("WARNING") + fmt.Sprintf(s, v...)
	_ = logger.logFile.Output(1, message)
	message = GetShellLogPrefix("WARNING") + fmt.Sprintf(s, v...)
	_ = logger.logStdout.Output(1, message)
}

func Verbose(s string, v ...interface{}) {
	logMutex.Lock()
	defer logMutex.Unlock()
	if logger.fileVerbosity >= LOGVERBOSE {
		message := GetLogPrefix("DEBUG") + fmt.Sprintf(s, v...)
		_ = logger.logFile.Output(1, message)
	}
	if logger.shellVerbosity >= LOGVERBOSE {
		message := GetShellLogPrefix("DEBUG") + fmt.Sprintf(s, v...)
		_ = logger.logStdout.Output(1, message)
	}
}

func Debug(s string, v ...interface{}) {
	logMutex.Lock()
	defer logMutex.Unlock()
	if logger.fileVerbosity >= LOGDEBUG {
		message := GetLogPrefix("DEBUG") + fmt.Sprintf(s, v...)
		_ = logger.logFile.Output(1, message)
	}
	if logger.shellVerbosity >= LOGDEBUG {
		message := GetShellLogPrefix("DEBUG") + fmt.Sprintf(s, v...)
		_ = logger.logStdout.Output(1, message)
	}
}

func Error(s string, v ...interface{}) {
	logMutex.Lock()
	defer logMutex.Unlock()
	errorCode = 1
	message := GetLogPrefix("ERROR") + fmt.Sprintf(s, v...)
	_ = logger.logFile.Output(1, message)
	message = GetShellLogPrefix("ERROR") + fmt.Sprintf(s, v...)
	_ = logger.logStderr.Output(1, message)
}

func Fatal(err error, s string, v ...interface{}) {
	stack := captureStack()
	logMutex.Lock()
	defer logMutex.Unlock()
	errorCode = 2
	message := ""
	stackTraceStr := ""
	if err != nil {
		message += fmt.Sprintf("%v", err)
		stackTraceStr = formatStackTrace(stack)
		if s != "" {
			message += ": "
		}
	}
	message += strings.TrimSpace(fmt.Sprintf(s, v...))
	fullMessage := GetLogPrefix("CRITICAL") + message
	_ = logger.logFile.Output(1, fullMessage+stackTraceStr)
	fullMessage = GetShellLogPrefix("CRITICAL") + message
	if logger.shellVerbosity >= LOGVERBOSE {
		abort(fullMessage + stackTraceStr)
	} else {
		abort(fullMessage)
	}
}

func Custom(customFileVerbosity int, customShellVerbosity int, s string, v ...interface{}) {
	logMutex.Lock()
	defer logMutex.Unlock()
	var message string
	if logger.fileVerbosity >= customFileVerbosity {
		message = GetLogPrefix(getVerbosityString(customFileVerbosity)) + fmt.Sprintf(s, v...)
		_ = logger.logFile.Output(1, message)
	}
	if customShellVerbosity == LOGERROR {
		message = GetShellLogPrefix("ERROR") + fmt.Sprintf(s, v...)
		_ = logger.logStderr.Output(1, message)
	} else if logger.shellVerbosity >= customShellVerbosity {
		message = GetShellLogPrefix(getVerbosityString(customShellVerbosity)) + fmt.Sprintf(s, v...)
		_ = logger.logStdout.Output(1, message)
	}
}

func FatalOnError(err error, output ...string) {
	if err != nil {
		if len(output) == 0 {
			Fatal(err, "")
		} else {
			Fatal(err, "%s", output[0])
		}
	}
}

func captureStack() []uintptr {
	var pcs [32]uintptr
	// Skip runtime.Callers, captureStack itself, and the gplog.Fatal frame
	// where captureStack runs — the trace should start at Fatal's caller.
	n := runtime.Callers(3, pcs[:])
	return pcs[:n]
}

func formatStackTrace(pcs []uintptr) string {
	var b strings.Builder
	frames := runtime.CallersFrames(pcs)
	for {
		f, more := frames.Next()
		if f.Function == "" {
			break
		}
		fmt.Fprintf(&b, "\n%s\n\t%s:%d", f.Function, f.File, f.Line)
		if !more {
			break
		}
	}
	return b.String()
}

func abort(output ...interface{}) {
	errStr := ""
	if len(output) > 0 {
		errStr = fmt.Sprintf("%v", output[0])
		if len(output) > 1 {
			errStr = fmt.Sprintf(errStr, output[1:]...)
		}
	}
	panic(errStr)
}

func openLogFile(filename string) io.WriteCloser {
	flags := os.O_APPEND | os.O_CREATE | os.O_WRONLY
	fileHandle, err := operating.System.OpenFileWrite(filename, flags, 0644)
	if err != nil {
		abort(err)
	}
	return fileHandle
}

func createLogDirectory(dirname string) {
	info, err := operating.System.Stat(dirname)
	if err != nil {
		if operating.System.IsNotExist(err) {
			err = operating.System.MkdirAll(dirname, 0755)
			if err != nil {
				abort(fmt.Errorf("Cannot create log directory %s: %v", dirname, err))
			}
		} else {
			abort(fmt.Errorf("Cannot stat log directory %s: %v", dirname, err))
		}
	} else if !info.IsDir() {
		abort(fmt.Errorf("%s is a file, not a directory", dirname))
	}
}
