declare module 'keycloak-js' {
  interface KeycloakConfig {
    url: string;
    realm: string;
    clientId: string;
  }

  interface KeycloakInitOptions {
    onLoad?: 'login-required' | 'check-sso';
    pkceMethod?: 'S256';
  }

  interface KeycloakLoginOptions {
    redirectUri?: string;
  }

  export default class Keycloak {
    constructor(config: KeycloakConfig);
    token?: string;
    tokenParsed?: Record<string, unknown>;
    init(options?: KeycloakInitOptions): Promise<boolean>;
    login(options?: KeycloakLoginOptions): Promise<void>;
    logout(options?: { redirectUri?: string }): Promise<void>;
  }
}
