#language slang 2026
[shader("miss")]
void main(inout uint payload) { payload = 99u; }
