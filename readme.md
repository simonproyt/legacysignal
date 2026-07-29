# legacysignal A signal client for old android devices or anything with an android runtime really like bb10 or whatever that does not require gms

- Whats working:
  - Captchas and displaying them trough geckoview because the built in android 4.x webview is too old to handle modern encryptions
  - sending the actual sms code (finally after 3 and a half hours of development hell its working)
- Whats not working:
  - singal itself i guess becuase the homescreen itself isnt even implamented yet so its a shell of a complete app rn

# Bugs

- becuase the app isnt implamented yet there is nothing to be bugged out (yet)
- you cant finish registration with becuase you will get a 401 error sometimes (working on it)
- you cant chose the country code in the country code picker somewhy (also working on it)

# Workarounds that i used to make this work on old android

- Replacing the stock webview with geckoview 70 like its not almost 6 years old but its still better then the built in chromium 3x based one with no tls1.2/1.3 support
- replacing the built in crypto libaries for the api stuff becuase it cant handle tls1.3 like the built in webview and it needed to go because of that
- replacing the old android crytofactory with a custom one cause the old built in one couldnt handle injecting modern certs into it
- having to backport java features/classes to the app becuase if they didnt exsist in our version the dalvikvm would just crash because it cant handle it like modern android i guess

# How to get yourself an apk:

So curently i dont have a github actions ci pipline set up (yet) becuase its stil not in the user usable phase you need to clone yourself the repo and built it yourself in android studio but in the future im planning on setting up a build pipeline so even non dev ppl can get it easily
