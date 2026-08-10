# legacysignal A signal client for old android devices or anything with an android runtime really like bb10 or whatever that does not require gms

- Whats working:
  - Captchas and displaying them trough geckoview because the built in android 4.x webview is too old to handle modern encryptions
  - sending the actual sms code (finally after 3 and a half hours of development hell its working)
- Whats not working:
  - singal itself i guess becuase the homescreen itself isnt even implamented yet so its a shell of a complete app rn

# Bugs

- becuase the app isnt implamented yet there is nothing to be bugged out (yet)
- all fixed curently i gueess

# Workarounds that i used to make this work on old android

- Replacing the stock webview with geckoview 70 like its not almost 6 years old but its still better then the built in chromium 3x based one with no tls1.2/1.3 support
- replacing the built in crypto libaries for the api stuff becuase it cant handle tls1.3 like the built in webview and it needed to go because of that
- replacing the old android crytofactory with a custom one cause the old built in one couldnt handle injecting modern certs into it
- having to backport java features/classes to the app becuase if they didnt exsist in our version the dalvikvm would just crash because it cant handle it like modern android i guess
- having to fork libsignal to backport symbols and replace new android apis with their old android 4.x ones to make it not crash and port its dependencies too

# How to get yourself an apk:

Use the releases tab and downlaoad it or build it yourself from source but first you need to patch some dependencies for libsignal but all of the patches that i used are in ci pipleine

# Acknowledgments:

- The signal project for the actual signal protocol and the libsignal crypto lib so i didnt need to write a crypto lib from scratch just have to backort and change a few stuff
- mozilla for making a viable chromium webview alternative that works on legacy stuff
