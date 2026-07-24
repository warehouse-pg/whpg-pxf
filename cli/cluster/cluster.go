package cluster

import (
	"bufio"
	"bytes"
	"context"
	joinerrs "errors"
	"fmt"
	"os"
	"os/exec"
	"path"
	"sort"
	"strconv"
	"strings"
	"time"

	"pxf-cli/dbconn"
	"pxf-cli/gplog"
	"pxf-cli/operating"
)

type Executor interface {
	ExecuteLocalCommand(commandStr string) (string, error)
	ExecuteLocalCommandWithContext(commandStr string, ctx context.Context) (string, error)
	ExecuteClusterCommand(scope Scope, commandList []ShellCommand) *RemoteOutput
	ExecuteClusterCommandWithRetries(scope Scope, commandList []ShellCommand, maxAttempts int, retrySleep time.Duration) *RemoteOutput
}

type GPDBExecutor struct{}

/*
 * A Cluster stores cluster topology three ways:
 *   - Segments: flat slice, ordered by content id (source of truth).
 *   - ByContent: content id -> []*SegConfig (primary first, mirror second).
 *   - ByHost: hostname -> []*SegConfig.
 * The maps hold pointers into Segments, so mutations are visible through both.
 */
type Cluster struct {
	ContentIDs []int
	Hostnames  []string
	Segments   []SegConfig
	ByContent  map[int][]*SegConfig
	ByHost     map[string][]*SegConfig
	Executor
}

type SegConfig struct {
	DbID          int    `db:"dbid"`
	ContentID     int    `db:"contentid"`
	Role          string `db:"role"`
	PreferredRole string `db:"preferredrole"`
	Mode          string `db:"mode"`
	Status        string `db:"status"`
	Port          int    `db:"port"`
	Hostname      string `db:"hostname"`
	Address       string `db:"address"`
	DataDir       string `db:"datadir"`
}

type Scope uint8

const (
	ON_SEGMENTS         Scope = 0
	ON_HOSTS            Scope = 1
	EXCLUDE_COORDINATOR Scope = 0
	INCLUDE_COORDINATOR Scope = 1 << 1
	EXCLUDE_MASTER      Scope = 0
	INCLUDE_MASTER      Scope = 1 << 1
	ON_REMOTE           Scope = 0
	ON_LOCAL            Scope = 1 << 2
	EXCLUDE_MIRRORS     Scope = 0
	INCLUDE_MIRRORS     Scope = 1 << 3
)

func scopeIsSegments(scope Scope) bool {
	return scope&ON_HOSTS == ON_SEGMENTS
}

func scopeIsHosts(scope Scope) bool {
	return scope&ON_HOSTS == ON_HOSTS
}

func scopeExcludesCoordinator(scope Scope) bool {
	return scope&INCLUDE_COORDINATOR == EXCLUDE_COORDINATOR
}

func scopeIncludesCoordinator(scope Scope) bool {
	return scope&INCLUDE_COORDINATOR == INCLUDE_COORDINATOR
}

func scopeIsRemote(scope Scope) bool {
	return scope&ON_LOCAL == ON_REMOTE
}

func scopeIsLocal(scope Scope) bool {
	return scope&ON_LOCAL == ON_LOCAL
}

func scopeExcludesMirrors(scope Scope) bool {
	return scope&INCLUDE_MIRRORS == EXCLUDE_MIRRORS
}

func scopeIncludesMirrors(scope Scope) bool {
	return scope&INCLUDE_MIRRORS == INCLUDE_MIRRORS
}

type ShellCommand struct {
	Scope         Scope
	Content       int
	Host          string
	Command       *exec.Cmd
	CommandString string
	Stdout        string
	Stderr        string
	Error         error
	RetryError    error
	Completed     bool
}

func NewShellCommand(scope Scope, content int, host string, command []string) ShellCommand {
	return ShellCommand{
		Scope:         scope,
		Content:       content,
		Host:          host,
		Command:       exec.Command(command[0], command[1:]...),
		CommandString: strings.Join(command, " "),
	}
}

type RemoteOutput struct {
	Scope           Scope
	NumErrors       int
	Commands        []ShellCommand
	FailedCommands  []ShellCommand
	RetriedCommands []ShellCommand
}

func NewRemoteOutput(scope Scope, numErrors int, commands []ShellCommand) *RemoteOutput {
	failedCommands := make([]ShellCommand, 0)
	retriedCommands := make([]ShellCommand, 0)
	for _, command := range commands {
		if command.Error != nil {
			failedCommands = append(failedCommands, command)
		} else if command.RetryError != nil {
			retriedCommands = append(retriedCommands, command)
		}
	}
	return &RemoteOutput{
		Scope:           scope,
		NumErrors:       numErrors,
		Commands:        commands,
		FailedCommands:  failedCommands,
		RetriedCommands: retriedCommands,
	}
}

func NewCluster(segConfigs []SegConfig) *Cluster {
	cluster := Cluster{}
	cluster.Segments = segConfigs
	cluster.ByContent = make(map[int][]*SegConfig, 0)
	cluster.ByHost = make(map[string][]*SegConfig, 0)
	cluster.Executor = &GPDBExecutor{}

	for i := range cluster.Segments {
		segment := &cluster.Segments[i]
		cluster.ByContent[segment.ContentID] = append(cluster.ByContent[segment.ContentID], segment)
		segmentList := cluster.ByContent[segment.ContentID]
		if len(segmentList) == 2 && segmentList[0].Role == "m" {
			// GetSegmentConfiguration always returns primaries before mirrors,
			// but if the caller built segConfigs by hand and put the mirror
			// first, swap so primary is always at index 0.
			segmentList[0], segmentList[1] = segmentList[1], segmentList[0]
		}
		cluster.ByHost[segment.Hostname] = append(cluster.ByHost[segment.Hostname], segment)
		if len(cluster.ByHost[segment.Hostname]) == 1 {
			cluster.Hostnames = append(cluster.Hostnames, segment.Hostname)
		}
	}
	for content := range cluster.ByContent {
		cluster.ContentIDs = append(cluster.ContentIDs, content)
	}
	sort.Ints(cluster.ContentIDs)
	return &cluster
}

/*
 * GenerateCommandList accepts either:
 *   - func(content int) []string for per-segment commands, or
 *   - func(host string)  []string for per-host commands.
 * The type switch picks the right one and panics on mismatch (programmer error).
 */
func (cluster *Cluster) GenerateCommandList(scope Scope, generator interface{}) []ShellCommand {
	commands := []ShellCommand{}
	switch generateCommand := generator.(type) {
	case func(content int) []string:
		for _, content := range cluster.ContentIDs {
			if content == -1 && scopeExcludesCoordinator(scope) {
				continue
			}
			commands = append(commands, NewShellCommand(scope, content, "", generateCommand(content)))
		}
	case func(host string) []string:
		for _, host := range cluster.Hostnames {
			hostHasOneContent := len(cluster.GetContentsForHost(host)) == 1
			if host == cluster.GetHostForContent(-1, "p") && scopeExcludesCoordinator(scope) && hostHasOneContent {
				continue
			}
			if host == cluster.GetHostForContent(-1, "m") && scopeExcludesMirrors(scope) && hostHasOneContent {
				continue
			}
			commands = append(commands, NewShellCommand(scope, -2, host, generateCommand(host)))
		}
	default:
		gplog.Fatal(nil, "Generator function passed to GenerateCommandList had an invalid function header.")
	}
	return commands
}

func ConstructSSHCommand(useLocal bool, host string, cmd string) []string {
	if useLocal {
		return []string{"bash", "-c", cmd}
	}
	currentUser, _ := operating.System.CurrentUser()
	user := currentUser.Username
	return []string{"ssh", "-o", "StrictHostKeyChecking=no", fmt.Sprintf("%s@%s", user, host), cmd}
}

func (cluster *Cluster) GenerateSSHCommandList(scope Scope, generator interface{}) []ShellCommand {
	var commands []ShellCommand
	localHost := cluster.GetHostForContent(-1)
	switch generateCommand := generator.(type) {
	case func(content int) string:
		commands = cluster.GenerateCommandList(scope, func(content int) []string {
			useLocal := (cluster.GetHostForContent(content) == localHost || scopeIsLocal(scope))
			cmd := generateCommand(content)
			return ConstructSSHCommand(useLocal, cluster.GetHostForContent(content), cmd)
		})
	case func(host string) string:
		commands = cluster.GenerateCommandList(scope, func(host string) []string {
			useLocal := (host == localHost || scopeIsLocal(scope))
			cmd := generateCommand(host)
			return ConstructSSHCommand(useLocal, host, cmd)
		})
	}
	return commands
}

func (executor *GPDBExecutor) ExecuteLocalCommand(commandStr string) (string, error) {
	output, err := exec.Command("bash", "-c", commandStr).CombinedOutput()
	return string(output), err
}

func (executor *GPDBExecutor) ExecuteLocalCommandWithContext(commandStr string, ctx context.Context) (string, error) {
	output, err := exec.CommandContext(ctx, "bash", "-c", commandStr).CombinedOutput()
	return string(output), err
}

func resetCmd(cmd *exec.Cmd) *exec.Cmd {
	args := cmd.Args
	return exec.Command(args[0], args[1:]...)
}

func (executor *GPDBExecutor) ExecuteClusterCommand(scope Scope, commandList []ShellCommand) *RemoteOutput {
	return executor.ExecuteClusterCommandWithRetries(scope, commandList, 1, 0)
}

/*
 * Run every command in commandList in its own goroutine. On failure, retry up
 * to maxAttempts with retrySleep between attempts. Each command's final stdout/
 * stderr/error is stored back into commandList in place.
 */
func (executor *GPDBExecutor) ExecuteClusterCommandWithRetries(scope Scope, commandList []ShellCommand, maxAttempts int, retrySleep time.Duration) *RemoteOutput {
	length := len(commandList)
	finished := make(chan int)
	numErrors := 0
	for i := range commandList {
		go func(index int) {
			var (
				out    []byte
				err    error
				stderr bytes.Buffer
			)
			command := commandList[index]
			for attempt := 1; attempt <= maxAttempts; attempt++ {
				stderr.Reset()
				cmd := resetCmd(command.Command)
				cmd.Stderr = &stderr
				out, err = cmd.Output()
				if err == nil {
					break
				} else {
					newRetryErr := fmt.Errorf("attempt %d: error was %w: %s", attempt, err, stderr.String())
					command.RetryError = joinerrs.Join(command.RetryError, newRetryErr)
					if attempt != maxAttempts {
						time.Sleep(retrySleep)
					}
				}
			}
			command.Stdout = string(out)
			command.Stderr = stderr.String()
			command.Error = err
			command.Completed = true
			commandList[index] = command
			finished <- index
		}(i)
	}
	for i := 0; i < length; i++ {
		index := <-finished
		if commandList[index].Error != nil {
			numErrors++
		}
	}
	return NewRemoteOutput(scope, numErrors, commandList)
}

/*
 * GenerateAndExecuteCommand wraps generation + execution for two common shapes:
 *   1. shell commands run directly on remote hosts via ssh
 *      (e.g. `ls` on every host)
 *   2. shell commands run on the coordinator that push work to remotes
 *      (e.g. multiple scps from the coordinator to all segments)
 */
func (cluster *Cluster) GenerateAndExecuteCommand(verboseMsg string, scope Scope, generator interface{}) *RemoteOutput {
	gplog.Verbose("%s", verboseMsg)
	commandList := cluster.GenerateSSHCommandList(scope, generator)
	return cluster.ExecuteClusterCommandWithRetries(scope, commandList, 5, 1*time.Second)
}

func (cluster *Cluster) CheckClusterError(remoteOutput *RemoteOutput, finalErrMsg string, messageFunc interface{}, noFatal ...bool) {
	for _, retriedCommand := range remoteOutput.RetriedCommands {
		switch messageFunc.(type) {
		case func(content int) string:
			content := retriedCommand.Content
			host := cluster.GetHostForContent(content)
			gplog.Debug("Command failed before passing on segment %d on host %s with error:\n%v", content, host, retriedCommand.RetryError)
		case func(host string) string:
			host := retriedCommand.Host
			gplog.Debug("Command failed before passing on host %s with error:\n%v", host, retriedCommand.RetryError)
		}
		gplog.Debug("Command was: %s", retriedCommand.CommandString)
	}

	if remoteOutput.NumErrors == 0 {
		return
	}
	for _, failedCommand := range remoteOutput.FailedCommands {
		errStr := fmt.Sprintf("with error %s: %s", failedCommand.Error, failedCommand.Stderr)
		switch getMessage := messageFunc.(type) {
		case func(content int) string:
			content := failedCommand.Content
			host := cluster.GetHostForContent(content)
			gplog.Custom(gplog.LOGERROR, gplog.LOGVERBOSE, "%s on segment %d on host %s %s", getMessage(content), content, host, errStr)
		case func(host string) string:
			host := failedCommand.Host
			gplog.Custom(gplog.LOGERROR, gplog.LOGVERBOSE, "%s on host %s %s", getMessage(host), host, errStr)
		}
		gplog.Verbose("Command was: %s", failedCommand.CommandString)
	}

	if len(noFatal) == 1 && noFatal[0] {
		gplog.Error("%s", finalErrMsg)
	} else {
		LogFatalClusterError(finalErrMsg, remoteOutput.Scope, remoteOutput.NumErrors)
	}
}

func LogFatalClusterError(errMessage string, scope Scope, numErrors int) {
	str := " on"
	if scopeIsLocal(scope) {
		str += " coordinator for"
	}
	errMessage += str

	segMsg := "segment"
	if scopeIsHosts(scope) {
		segMsg = "host"
	}
	if numErrors != 1 {
		segMsg += "s"
	}
	gplog.Fatal(fmt.Errorf("%s %d %s. See %s for a complete list of errors.", errMessage, numErrors, segMsg, gplog.GetLogFilePath()), "")
}

func getSegmentByRole(segmentList []*SegConfig, role ...string) *SegConfig {
	if len(role) == 1 && role[0] == "m" {
		if len(segmentList) < 2 {
			return nil
		}
		return segmentList[1]
	}
	if len(segmentList) == 0 {
		return nil
	}
	return segmentList[0]
}

func (cluster *Cluster) GetDbidForContent(contentID int, role ...string) int {
	segConfig := getSegmentByRole(cluster.ByContent[contentID], role...)
	if segConfig == nil {
		return -1
	}
	return segConfig.DbID
}

func (cluster *Cluster) GetPortForContent(contentID int, role ...string) int {
	segConfig := getSegmentByRole(cluster.ByContent[contentID], role...)
	if segConfig == nil {
		return -1
	}
	return segConfig.Port
}

func (cluster *Cluster) GetHostForContent(contentID int, role ...string) string {
	segConfig := getSegmentByRole(cluster.ByContent[contentID], role...)
	if segConfig == nil {
		return ""
	}
	return segConfig.Hostname
}

func (cluster *Cluster) GetDirForContent(contentID int, role ...string) string {
	segConfig := getSegmentByRole(cluster.ByContent[contentID], role...)
	if segConfig == nil {
		return ""
	}
	return segConfig.DataDir
}

func (cluster *Cluster) GetDbidsForHost(hostname string) []int {
	dbids := make([]int, len(cluster.ByHost[hostname]))
	for i, seg := range cluster.ByHost[hostname] {
		dbids[i] = seg.DbID
	}
	return dbids
}

func (cluster *Cluster) GetContentsForHost(hostname string) []int {
	contents := make([]int, len(cluster.ByHost[hostname]))
	for i, seg := range cluster.ByHost[hostname] {
		contents[i] = seg.ContentID
	}
	return contents
}

func (cluster *Cluster) GetPortsForHost(hostname string) []int {
	ports := make([]int, len(cluster.ByHost[hostname]))
	for i, seg := range cluster.ByHost[hostname] {
		ports[i] = seg.Port
	}
	return ports
}

func (cluster *Cluster) GetDirsForHost(hostname string) []string {
	dirs := make([]string, len(cluster.ByHost[hostname]))
	for i, seg := range cluster.ByHost[hostname] {
		dirs[i] = seg.DataDir
	}
	return dirs
}

/*
 * GetSegmentConfiguration reads gp_segment_configuration. By default it returns
 * only primaries (and coordinator). Pass true to include mirrors+standby; pass
 * a second true to include mirrors+standby exclusively.
 */
func GetSegmentConfiguration(connection *dbconn.DBConn, getMirrors ...bool) ([]SegConfig, error) {
	includeMirrors := len(getMirrors) == 1 && getMirrors[0]
	includeOnlyMirrors := len(getMirrors) == 2 && getMirrors[1]
	query := ""
	if connection.Version.Before("6") {
		whereClause := "WHERE%s f.fsname = 'pg_system'"
		if includeOnlyMirrors {
			whereClause = fmt.Sprintf(whereClause, " s.role = 'm' AND")
		} else if includeMirrors {
			whereClause = fmt.Sprintf(whereClause, "")
		} else {
			whereClause = fmt.Sprintf(whereClause, " s.role = 'p' AND")
		}
		query = fmt.Sprintf(`
SELECT
	s.dbid,
	s.content as contentid,
	s.role,
	s.preferred_role as preferredrole,
	s.mode,
	s.status,
	s.port,
	s.hostname,
	s.address,
	e.fselocation as datadir
FROM gp_segment_configuration s
JOIN pg_filespace_entry e ON s.dbid = e.fsedbid
JOIN pg_filespace f ON e.fsefsoid = f.oid
%s
ORDER BY s.content, s.role DESC;`, whereClause)
	} else {
		whereClause := "WHERE role = 'p'"
		if includeOnlyMirrors {
			whereClause = "WHERE role = 'm'"
		} else if includeMirrors {
			whereClause = ""
		}
		query = fmt.Sprintf(`
SELECT
	dbid,
	content as contentid,
	role,
	preferred_role as preferredrole,
	mode,
	status,
	port,
	hostname,
	address,
	datadir
FROM gp_segment_configuration
%s
ORDER BY content, role DESC;`, whereClause)
	}

	results := make([]SegConfig, 0)
	err := connection.Select(&results, query)
	if err != nil {
		return nil, err
	}
	return results, nil
}

func MustGetSegmentConfiguration(connection *dbconn.DBConn, getMirrors ...bool) []SegConfig {
	segConfigs, err := GetSegmentConfiguration(connection, getMirrors...)
	gplog.FatalOnError(err)
	return segConfigs
}

/*
 * GetSegmentConfigurationFromFile reads gp_segment_configuration from
 * $COORDINATOR_DATA_DIR/gpsegconfig_dump. Use when the database is down;
 * otherwise prefer GetSegmentConfiguration. The dump file is written by FTS,
 * so its contents can lag the live configuration by an FTS interval.
 *
 * File format is whitespace-separated, one segment per line. Older versions
 * have 9 fields (no datadir); newer versions have 10 (with datadir):
 *   <dbid> <content> <role> <prefRole> <mode> <status> <port> <host> <addr> [datadir]
 */
func GetSegmentConfigurationFromFile(coordinatorDataDir string) ([]SegConfig, error) {
	if len(strings.TrimSpace(coordinatorDataDir)) == 0 {
		return nil, fmt.Errorf("Coordinator data directory path is empty")
	}

	gpsegconfigDump := path.Join(coordinatorDataDir, "gpsegconfig_dump")
	fd, err := os.Open(gpsegconfigDump)
	if err != nil {
		return nil, fmt.Errorf("Failed to open file %s. Error: %s", gpsegconfigDump, err.Error())
	}
	defer fd.Close()

	results := make([]SegConfig, 0)
	scanner := bufio.NewScanner(fd)

	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		parts := len(fields)
		if parts != 9 && parts != 10 {
			return nil, fmt.Errorf("Unexpected number of fields (%d) in line: %s", parts, scanner.Text())
		}

		dbID, err := strconv.Atoi(fields[0])
		if err != nil {
			return nil, fmt.Errorf("Failed to convert dbID with value %s to an int. Error: %s", fields[0], err.Error())
		}

		content, err := strconv.Atoi(fields[1])
		if err != nil {
			return nil, fmt.Errorf("Failed to convert content with value %s to an int. Error: %s", fields[1], err.Error())
		}

		port, err := strconv.Atoi(fields[6])
		if err != nil {
			return nil, fmt.Errorf("Failed to convert port with value %s to an int. Error: %s", fields[6], err.Error())
		}

		datadir := ""
		if parts == 10 {
			datadir = fields[9]
		}

		results = append(results, SegConfig{
			DbID:          dbID,
			ContentID:     content,
			Role:          fields[2],
			PreferredRole: fields[3],
			Mode:          fields[4],
			Status:        fields[5],
			Port:          port,
			Hostname:      fields[7],
			Address:       fields[8],
			DataDir:       datadir,
		})
	}

	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("Failed to read gpsegconfig_dump file %s: %s", gpsegconfigDump, err.Error())
	}

	return results, nil
}
