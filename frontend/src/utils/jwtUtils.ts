/**
 * JWT utility functions for decoding claims for presentation purposes.
 *
 * CRITICAL SECURITY NOTICE:
 * Client-side decoding of JWTs is strictly for UI display and presentation.
 * It DOES NOT cryptographically verify the signature, issuer key, or tampering.
 * The frontend MUST NEVER make authorization or security decisions based on
 * decoded token claims. All authorization and access control is enforced
 * strictly by the Spring Boot backend.
 */

export interface DecodedJwtPayload {
  sub?: string;
  roles?: string[];
  iss?: string;
  aud?: string | string[];
  exp?: number; // epoch seconds
  iat?: number; // epoch seconds
  nbf?: number; // epoch seconds
  [key: string]: unknown;
}

/**
 * Safely decodes base64url-encoded JWT claims without external dependencies.
 * Returns null if the token is null, malformed, or cannot be parsed.
 */
export function decodeJwtClaims(token: string | null | undefined): DecodedJwtPayload | null {
  if (!token || typeof token !== 'string') {
    return null;
  }

  const parts = token.trim().split('.');
  if (parts.length !== 3) {
    return null;
  }

  try {
    // Replace URL-safe base64 characters
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    // Pad base64 string if necessary
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');

    // Decode base64 to binary string then to UTF-8 URI string
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    const decoder = new TextDecoder('utf-8');
    const jsonString = decoder.decode(bytes);

    return JSON.parse(jsonString) as DecodedJwtPayload;
  } catch {
    return null;
  }
}

/**
 * Checks whether a token has expired based on its 'exp' claim.
 * Includes a configurable clock-skew safety margin (default: 5 seconds).
 */
export function isTokenExpired(token: string | null | undefined, clockSkewSeconds = 5): boolean {
  const claims = decodeJwtClaims(token);
  if (!claims || typeof claims.exp !== 'number') {
    return true;
  }
  const nowSeconds = Math.floor(Date.now() / 1000);
  return claims.exp <= nowSeconds + clockSkewSeconds;
}
