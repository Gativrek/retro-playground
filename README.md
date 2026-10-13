# Retro Playground

Emulador de Atari 2600 para Android, minimalista e em português. Feito para jogar **Enduro**,
**River Raid** e **Seaquest** (e outros cartuchos comuns) com controles na tela.

- Lê arquivos `.a26` (e `.bin`) de uma pasta escolhida por você.
- Lista os jogos pelo nome (tags como `(1983) (Activision)` são removidas).
- **Abrir pasta** mostra a pasta dos jogos no gerenciador de arquivos; **Trocar pasta** escolhe outra.
- Controles: direcional de 8 direções (metade esquerda da tela), botão de tiro (metade direita),
  **INICIAR** (chave Reset), **MODO** (chave Select) e **SAIR**. Controles físicos e teclado também funcionam.
- Sem anúncios, sem internet, sem permissões. APK de release com ~50 KB.

## Como usar

1. Copie seus `.a26` para uma pasta do celular, por exemplo `Download/Atari`
   (o Android não deixa escolher a raiz de `Download`, use uma subpasta).
2. Abra o app → **Escolher pasta** → selecione a pasta → **Usar esta pasta** → **Permitir**.
3. Toque no nome do jogo. Em geral os jogos começam com **INICIAR** ou com o botão de tiro.

## Emulador

Núcleo próprio em Kotlin puro (`app/src/main/java/.../emu`), sem NDK nem bibliotecas:

| Peça | Arquivo | Notas |
|---|---|---|
| CPU 6507 | `Cpu6502.kt` | Precisão de ciclo (inclui leituras "fantasmas"), modo decimal, opcodes ilegais estáveis. Passa no teste funcional de Klaus Dormann. |
| TIA (vídeo + som) | `Tia.kt` | Um color clock por vez; contadores de posição como no hardware (HMOVE, barras pretas da Activision, cópias NUSIZ, VDEL, colisões). Som com contadores polinomiais. |
| RIOT | `Riot.kt` | Joystick, chaves do console, timer. |
| Cartuchos | `Cartridge.kt` | 2K, 4K, F8, F6, F4 (com Superchip), E0 (Parker Bros) e 3F (Tigervision). |

O ritmo vem do áudio (31,4 kHz, 2 amostras por linha), então o jogo roda na velocidade exata do NTSC.

## Compilar

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # testes do núcleo na JVM
```

Testes opcionais:
- `testdata/6502_functional_test.bin` (de [Klaus2m5/6502_65C02_functional_tests](https://github.com/Klaus2m5/6502_65C02_functional_tests)) ativa o teste completo da CPU.
- Capturas de tela de cada jogo em `app/build/test-shots/` (PowerShell):
  `$env:ROMS_DIR="D:\Claude\Retro2600\roms"; .\gradlew testDebugUnitTest --tests "*romScreenshots*"`
  (guarde as ROMs fora de `app/build`, que é apagado por `gradlew clean`).

Assinatura de release: crie `keystore.properties` (ignorado pelo git) com `storeFile`,
`storePassword`, `keyAlias` e `keyPassword`.

As ROMs não fazem parte deste projeto — use apenas cartuchos que você possui.

## Fontes e ícone

Interface desenhada no canvas "Retro Playground — UI & Icon". Fontes incluídas em `app/src/main/res/font/`,
todas sob a SIL Open Font License 1.1: Chakra Petch (Cadson Demak) e IBM Plex Sans/Mono (IBM).
