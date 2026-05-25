import base64
import sys

KEY = "PokeGraderSecureKey2026"

def xor_cipher(text, key):
    return "".join(chr(ord(c) ^ ord(key[i % len(key)])) for i, c in enumerate(text))

def encrypt_string(text, key=KEY):
    xor_str = xor_cipher(text, key)
    return base64.b64encode(xor_str.encode('latin1')).decode('utf-8')

def decrypt_string(encrypted_text, key=KEY):
    raw_bytes = base64.b64decode(encrypted_text.encode('utf-8'))
    decoded_str = raw_bytes.decode('latin1')
    return xor_cipher(decoded_str, key)

def main():
    print("=== PokeGrader Credentials Obfuscator ===")
    print(f"Using Symmetric Key: {KEY}\n")
    
    api_key = input("Enter your raw Firebase Web API Key: ").strip()
    db_url = input("Enter your raw Firebase Realtime Database URL: ").strip()
    
    if not api_key or not db_url:
        print("Error: Both fields are required.")
        sys.exit(1)
        
    enc_api_key = encrypt_string(api_key)
    enc_db_url = encrypt_string(db_url)
    
    print("\n" + "="*50)
    print("OBFUSCATED SECRETS GENERATED SUCCESSFULY!")
    print("="*50)
    print("\n>>> FOR PC (paste inside: binder/backend/firebase_secrets.py):")
    print(f'ENC_FIREBASE_API_KEY = "{enc_api_key}"')
    print(f'ENC_FIREBASE_DB_URL = "{enc_db_url}"')
    
    print("\n>>> FOR MOBILE (paste inside: gradingAPP/secrets.properties):")
    print(f'ENC_FIREBASE_API_KEY={enc_api_key}')
    print(f'ENC_FIREBASE_DB_URL={enc_db_url}')
    print("="*50 + "\n")

if __name__ == "__main__":
    main()
