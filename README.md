# AWS IDP SAML UI Client

A Java Swing-based desktop application for AWS SAML authentication. This tool provides a user-friendly interface to manage SAML-based authentication with AWS Identity Providers, handle credentials, tokens, and profiles.

## Features

- **SAML Authentication**: Parse and authenticate using SAML assertions from identity providers
- **Credential Management**: Securely store and manage AWS credentials
- **Token Management**: Handle temporary security tokens and their expiration
- **Profile Support**: Manage multiple AWS profiles and configurations
- **Database Storage**: Persistent storage using SQLite for credentials and settings
- **Modern UI**: Swing interface with FlatLaf theming support
- **Browser Integration**: Automated browser login handling with Selenium WebDriver
- **Configuration Management**: INI-based configuration files
- **Password Management**: Secure password handling and encryption

## Prerequisites

- Java 24 or higher
- Maven 3.6+
- Web browser (for SAML authentication flow)

## Installation

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd aws-idp-saml-ui
   ```

2. Build the project:
   ```bash
   mvn clean compile
   ```

3. Package the application:
   ```bash
   mvn package
   ```

## Usage

Run the application:
```bash
java -jar target/aws-idp-saml-ui.jar
```

### Main Interface

- **Profile Selection**: Choose from configured AWS profiles
- **Fast Pass Option**: Enable/disable fast authentication mode
- **Request Credentials**: Initiate SAML authentication flow
- **Token Status**: View current token expiration and status
- **Configuration**: Access settings and preferences

### Configuration

The application uses INI configuration files and SQLite database for persistent storage. Configuration is managed through the UI dialogs.

## Dependencies

- **Selenium WebDriver**: Browser automation for SAML login
- **AWS SDK for Java**: STS operations for temporary credentials
- **Apache XML Security**: SAML assertion processing
- **SQLite JDBC**: Database operations
- **FlatLaf**: Modern Swing look and feel
- **SLF4J/Logback**: Logging framework
- **Apache Commons**: Utility functions

## Development

### Building from Source

```bash
mvn clean install
```

### Project Structure

```
src/main/java/com/ourgiant/saml/
├── SwingMain.java              # Main application window
├── SamlAuthenticator.java      # SAML authentication logic
├── SamlParser.java             # SAML assertion parsing
├── CredentialManager.java      # AWS credential management
├── TokenStateManager.java      # Token lifecycle management
├── DatabaseManager.java        # SQLite database operations
├── ConfigManager.java          # Configuration handling
├── PasswordManager.java        # Password encryption/decryption
├── BrowserLoginHandler.java    # Browser automation
├── ConfigurationDialog.java    # Settings UI
├── CredentialsDialog.java      # Credential management UI
├── ThemeManager.java           # UI theming
└── SamlRole.java               # SAML role representation
```

## License

See LICENSE file for details.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Troubleshooting

- Ensure Java 24 is installed and JAVA_HOME is set correctly
- Check browser compatibility for Selenium WebDriver
- Verify AWS configuration and SAML provider settings
- Review logs in the application directory for error details