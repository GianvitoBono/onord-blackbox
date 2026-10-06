use rand::{rngs::OsRng, RngCore};

pub fn short_token() -> String {
    // 20 independent base32 characters: 100 bits, grouped for manual entry.
    const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    let mut random = [0u8; 20];
    OsRng.fill_bytes(&mut random);
    let mut token = String::with_capacity(23);
    for (index, value) in random.iter().enumerate() {
        if index > 0 && index % 5 == 0 {
            token.push('-');
        }
        token.push(ALPHABET[(value & 31) as usize] as char);
    }
    token
}
