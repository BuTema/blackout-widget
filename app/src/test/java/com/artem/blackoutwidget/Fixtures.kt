package com.artem.blackoutwidget

/** Trimmed copies of real DTEK Odesa responses captured on 2026-11-04. */
object Fixtures {
    /** GPV6.2: off 00-04, on 04-10, off 10-18, on 18-21, off 21-24. */
    const val G62 =
        """{"1":"no","2":"no","3":"no","4":"no","5":"yes","6":"yes","7":"yes","8":"yes","9":"yes","10":"yes","11":"no","12":"no","13":"no","14":"no","15":"no","16":"no","17":"no","18":"no","19":"yes","20":"yes","21":"yes","22":"no","23":"no","24":"no"}"""

    /** GPV1.1 has half-hour entries: "second" at hour 4, "first" at hours 8 and 22. */
    const val G11 =
        """{"1":"yes","2":"yes","3":"yes","4":"second","5":"no","6":"no","7":"no","8":"first","9":"yes","10":"yes","11":"yes","12":"yes","13":"yes","14":"yes","15":"no","16":"no","17":"no","18":"no","19":"no","20":"no","21":"no","22":"first","23":"yes","24":"yes"}"""

    /** 1793743200 = 2026-11-03T22:00:00Z = 2026-11-04 00:00 in Kyiv. */
    const val FACT =
        """{"data":{"1793743200":{"GPV1.1":$G11,"GPV6.2":$G62}},"update":"04.11.2026 09:44","today":1793743200}"""

    /** Shape of the getHomeNum response; the key "9а" uses a Cyrillic letter. */
    const val HOME =
        """{"result":true,"data":{
          "9":{"sub_type":"x","start_date":"10:04 04.11.2026","end_date":"18:00 04.11.2026","type":"2","sub_type_reason":["GPV6.2"]},
          "9а":{"sub_type":"x","start_date":"10:04 04.11.2026","end_date":"18:00 04.11.2026","type":"2","sub_type_reason":["GPV6.2"]},
          "3б":{"sub_type":"x","type":"2","sub_type_reason":["GPV1.1"]},
          "5":{"sub_type":"x","type":"2","sub_type_reason":[]}
        },"showCurOutageParam":true,"updateTimestamp":"11:47 04.11.2026"}"""
}
