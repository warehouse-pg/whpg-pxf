package org.greenplum.pxf.automation.components.common.cli;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.PTYMode;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive;
import net.schmizz.sshj.userauth.method.AuthPassword;
import net.schmizz.sshj.userauth.method.PasswordResponseProvider;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import systemobject.terminal.Terminal;

/**
 * JSystem {@link Terminal} that drives an interactive shell over SSH with
 * <a href="https://github.com/hierynomus/sshj">sshj</a>.
 * <p>
 * JSystem's own {@code systemobject.terminal.SSH} (and the previous version of
 * this class, which extended it) opened the connection with Ganymed SSH2, a
 * 2006-era client that only speaks diffie-hellman-group1/group14-sha1 KEX and
 * CBC ciphers. OpenSSH 9.x builds drop those algorithms at compile time (the
 * sshd {@code +algo} re-enable modifier can only turn back on what was built
 * in), so Ganymed shares no handshake crypto with an el9 sshd and cannot
 * connect to it. sshj negotiates the current defaults (curve25519 / ecdh KEX,
 * aes-ctr / gcm and chacha20 ciphers, rsa-sha2 / ed25519 host keys) and reads
 * private keys in the OpenSSH, PEM and PuTTY formats, so neither the sshd
 * legacy-algorithm block nor the OpenSSH-to-PEM key conversion the Ganymed
 * harness required is needed any more.
 * <p>
 * Authentication: a non-empty {@code password} is tried first (password and
 * keyboard-interactive); otherwise the {@code privateKey} file is used for
 * public-key authentication. Host keys are not verified: the harness talks to
 * the hosts named in the SUT, the same trust model Ganymed had.
 * <p>
 * The shell is started on a {@code dumb} PTY, as before: terminal-aware
 * commands otherwise colour their output and the escape sequences break the
 * prompt matching (seen on RHEL 9 with an xterm PTY).
 */
public class SshRsa extends Terminal {

	public static final int DEFAULT_PORT = 22;

	private final String hostname;
	private final int port;
	private final String username;
	private final String password;
	private File privateKeyFile;

	private SSHClient client;
	private Session session;
	private Session.Shell shell;

	public SshRsa(String hostnameP, String usernameP, String passwordP, File privateKey) {
		this(hostnameP, DEFAULT_PORT, usernameP, passwordP, privateKey);
	}

	public SshRsa(String hostnameP, int portP, String usernameP, String passwordP, File privateKey) {
		super();
		hostname = hostnameP;
		port = portP;
		username = usernameP;
		password = passwordP;
		privateKeyFile = privateKey;
	}

	@Override
	public void connect() throws IOException {
		SSHClient ssh = new SSHClient();
		ssh.addHostKeyVerifier(new PromiscuousVerifier());
		try {
			System.out.println("Connecting to " + username + "@" + hostname + ":" + port + " with SSH (sshj)");
			ssh.connect(hostname, port);
			authenticate(ssh);

			session = ssh.startSession();
			/*
			 * There are commands in automation that are terminal-aware and return
			 * colored output. This was causing extraneous output when using an xterm
			 * terminal in automation tests on RHEL9, causing tests to fail. This does
			 * not happen with a "dumb" PTY as the commands will not emit ANSI escape
			 * sequences. Zero window size, as Ganymed's requestDumbPTY() sent.
			 */
			session.allocatePTY("dumb", 0, 0, 0, 0, Collections.<PTYMode, Integer> emptyMap());
			shell = session.startShell();

			in = shell.getInputStream();
			out = shell.getOutputStream();
			client = ssh;
		} catch (IOException | RuntimeException e) {
			closeQuietly(ssh);
			session = null;
			shell = null;
			throw e;
		}
	}

	private void authenticate(SSHClient ssh) throws IOException {
		if (password != null && !password.isEmpty()) {
			System.out.println("Authenticating with password");
			ssh.auth(username,
					new AuthPassword(PasswordUtils.createOneOff(password.toCharArray())),
					new AuthKeyboardInteractive(new PasswordResponseProvider(
							PasswordUtils.createOneOff(password.toCharArray()))));
			return;
		}
		if (privateKeyFile == null || !privateKeyFile.isFile()) {
			throw new IOException("Auth Error - no password given and no private key file to use ("
					+ (privateKeyFile == null ? "privateKey not set" : privateKeyFile.getAbsolutePath())
					+ "); set privateKey in the SUT to an RSA/ed25519 private key readable by this user");
		}
		System.out.println("Authenticating with private key " + privateKeyFile.getAbsolutePath());
		ssh.authPublickey(username, ssh.loadKeys(privateKeyFile.getAbsolutePath()));
	}

	@Override
	public void disconnect() {
		try {
			closeStreams();
		} catch (IOException e) {
			// best effort: the channel is being torn down anyway
		}
		if (shell != null) {
			try {
				shell.close();
			} catch (IOException e) {
				// ignore, closing the session below tears the channel down
			}
			shell = null;
		}
		if (session != null) {
			try {
				session.close();
			} catch (IOException e) {
				// ignore
			}
			session = null;
		}
		closeQuietly(client);
		client = null;
	}

	private static void closeQuietly(SSHClient ssh) {
		if (ssh == null) {
			return;
		}
		try {
			ssh.disconnect();
		} catch (IOException e) {
			// ignore
		}
	}

	@Override
	public boolean isConnected() {
		return client != null && client.isConnected() && client.isAuthenticated()
				&& session != null && session.isOpen();
	}

	@Override
	public String getConnectionName() {
		return "SSH_RSA";
	}

	public String getHostname() {
		return hostname;
	}

	public int getPort() {
		return port;
	}

	public String getUsername() {
		return username;
	}

	public File getPrivateKeyFile() {
		return privateKeyFile;
	}

	public void setPrivateKeyFile(File privateKeyFile) {
		this.privateKeyFile = privateKeyFile;
	}
}
