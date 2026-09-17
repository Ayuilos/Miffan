# JSch looks up key generators, signatures, ciphers, hashes and KDFs by class-name strings
# in its algorithm registry. R8 cannot infer those registry edges. Preserve the implementations
# and their constructors/methods for both SSH authentication and offline private-key import.
# The excluded integrations require desktop-only/optional dependencies and are not used by
# Miffan's password/public-key authentication. Do not retain them or suppress missing classes.
-keep class !com.jcraft.jsch.PageantConnector,!com.jcraft.jsch.Log4j2Logger,!com.jcraft.jsch.JUnixSocketFactory,!com.jcraft.jsch.SSHAgentConnector,!com.jcraft.jsch.jgss.**,com.jcraft.jsch.** { *; }
