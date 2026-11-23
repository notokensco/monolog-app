package com.notokens.monolog.ime

internal object TypingVocabulary {
    // Higher priority for everyday chat vocabulary when multiple swipe shapes match.
    val english = words("""
        the and you that have for with this from they will would there their what about which when
        make like time just know take into your good some could them then look only come think also
        back after want because these give work first well way even new can need how see let use
        our out day now here today tomorrow tonight yesterday morning afternoon evening night
        sure yes okay thanks thank please sorry hello hi hey welcome fine really nice great awesome
        getting coming going leaving waiting working meeting call message text send share check
        later soon next leave stay start finish stop keep ready plan reminder alarm note remember
        home office school college library class exam study dinner lunch breakfast coffee water
        weekend monday tuesday wednesday thursday friday saturday sunday today around minutes hours
        reach arrive pick drop bring buy book ticket bookshop train metro bus taxi flight trip
        friend family brother sister mother father mum mom dad everyone someone anyone nobody
        my me mine rather than enough possible maybe probably still already always never again
        right left where why doing done does did under over before between together through
        little much many more most less same different another thing things something nothing
        question answer reading writing could should would need needed important take care
        workday holiday birthday happy better best may might must help talk speak told said
        tiffin lakh lakhs crore crores prepaid pincode petrol colour favourite organise centre
        tuition rickshaw chaat cricket naan dosa idli curry biryani dhaba airtel indigo mumbai
        delhi bengaluru chennai hyderabad kolkata pune kochi jaipur surat guwahati
    """)

    val hinglish = words("""
        hai hain ho hun hoon hu tha thi the kya kyun kyu kyon kaise kaisa kaisi kahan kaha kab kitna
        mai main mein hum tum aap tu mera meri mere tera teri tere apna apni apne hamara hamari
        ye yeh woh vo wo usko isko unko mujhe muje tumhe aapko humko sab koi kuch ab abhi fir phir
        aaj kal parso subah dopahar shaam sham raat din baje roz jaldi dheere der baad pehle
        haan han nahi nahin nai na acha achha accha achha thik theek sahi galat bilkul pakka
        bas bhi aur ya lekin magar par toh to se ke ki ka ko pe mein tak liye wala wali wale
        ghar school college office bazaar dukaan station metro gaadi gadi train bus auto
        jaana jana jaa jaao ja raha rahi rahe aa aana ana aao aaja aajao aayega aaunga aaungi
        gaya gayi gaye jayega jaayega jaunga jaungi aaya aayi aaye mil milna milte milenge milunga
        kar karo karna karunga karungi kariye karega karenge karta karti karte karlo kardena
        de dena dedo dede denge diya diye le lena lelo leke lao lana laana launga
        bhej bhejo bhejna bhejdo bata batao batana bol bolo bolna sun suno sunna dekho dekh
        dekhna soch socho sochna padhna padna padh padha kaam kaamwali padhai kitab kitaab
        khana khaana kha khaao chai pani paani doodh nashta paisa paise rupaye kitne sabzi
        dost yaar bhai behen didi bhaiya mummy papa amma abba namaste namaskar shukriya
        dhanyavaad maaf sorry mubarak khushi khush dukhi gussa fikar tension mast badiya badhiya
        zaroor jarur zaruri zaroori madad samay waqt yaad rakh rakhna rakho yaadkar
        ho jayega ho gaya nahi hua hoga hua hui hue sakta sakti sakte chahiye chahie
        milkar saath sath alag taraf yahan wahan idhar udhar saamne samne andar bahar upar
        niche neeche ek do teen char chaar paanch che saat aath nau das apun scene jugaad
        majak mazaak badme baadme thoda thodi thode zyada jyada bahut bohot kam itna itni
        mujhey tujhe tujhey tumko humne aapne usne isne unhone inhone kisko kiske kiski
        kisne kuchh koi bhi wahi wohi waisa waise waisehi aise aisa aisi isliye kyunki
        samajh samjha samjhi samjho samajhna samjhana samjhao bataya batayi bheja bheji
        pahunch pohonch pahuncha pahunchi pahunchna pohonchna pahunchunga pahunchungi
        nikla nikli nikle nikal niklo nikalna aane jaane karne lene dene hone waali
        karwana karwa karwao karwaya karwaunga hojana hogaya hogayi hogaye hojayega
        aayegi aayenge aaoge aayoge jaogi jaoge jaayegi jaayenge aata aati aate jaata
        jaati jaate jaatehi rehta rehti rehte rehna ruko rukna rukoge rakhunga uthna
        utha uthao uthana uthunga baith baitho baithna baitha baithe sojao sona soya
        soyi uthgaya jagna khol kholo kholna band bandh khula khuli lagta lagti lagte
        laga lagi lage lagana milta milti milega milegi mile pooch puch poochna puchna
        poochho pucho pucha poocha kaha kaho kehna kehdo kehdiya kehraha sunke dekhke
        dekhliya dekhlena dekhlo dekhlenge dikh dikhna dikhao dikha dikhaya dikhayi
        chalo chalna chale chalen chali chalta chalti chalte chalenge chalega chaltaa
        karke karkehi kardo karnahe karliye kardiya kardiyaah kardoge kardungi karoge
        wapas vaapas vapas laut aage piche peeche nazdeek paas door dur bagal beech
        zarurat jarurat dikkat pareshani pareshaan pareeksha pariksha parwah jawab
        sawaal sawal prashna haalat tabiyat sehat dawa dava davai dawaai bukhar
        barish baarish mausam garmi sardi thand thandi garam dhoop cheezein cheez
        samaan saamaan kharcha kharch mehenga mahanga sasta hafte hafta mahina saal
        somvaar mangalvaar budhvaar guruvaar shukravaar shanivaar ravivaar sacchi
        sach sachchi sachme bilkulhi ekdum ekdam kafi kaafi lagbhag shayad shayed
        dobara dubaara phirse firse roj humesha hamesha turant waise actually
    """)

    val hinglishPairs = mapOf(
        "aap" to listOf("kaise", "kahan", "ho"), "tum" to listOf("kya", "kahan", "ho"),
        "main" to listOf("abhi", "ghar", "kal"), "kal" to listOf("subah", "milte", "shaam"),
        "kya" to listOf("hai", "kar", "hua"), "ghar" to listOf("pe", "ja", "aao"),
        "mujhe" to listOf("chahiye", "yaad", "batao"), "theek" to listOf("hai"),
        "kar" to listOf("raha", "rahi", "do"), "milte" to listOf("hain", "hai"),
        "aaj" to listOf("shaam", "kal", "raat"), "bahut" to listOf("achha", "zyada"),
        "ho" to listOf("gaya", "gayi", "jayega"), "nahi" to listOf("hai", "hoga")
    )

    private fun words(value: String) = value.trim().split(Regex("\\s+")).toSet()
}
